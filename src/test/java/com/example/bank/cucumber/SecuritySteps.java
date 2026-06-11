package com.example.bank.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.example.bank.data.model.AccountEntity;
import com.example.bank.data.model.CardEntity;
import com.example.bank.data.repo.AccountRepo;
import com.example.bank.data.repo.CardRepo;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * Regression-locks the dual session/JWT auth security matrix: anonymous callers,
 * kiosk sessions (own-account only), JWT scopes (atm.read/atm.write/atm.ops),
 * garbage-bearer precedence, and actuator access.
 *
 * <p>Scenario-scoped (fresh instance per scenario). The two security test accounts
 * (SecAlice / SecBob) are seeded idempotently in the {@link #setup()} {@code @Before}
 * hook and shared across all scenarios via static state — the same pattern used by
 * {@code AccountTransactionSteps.bearerToken}. The shared DB accumulates rows across
 * scenarios by design; only the card-number uniqueness constraint requires idempotent
 * seeding rather than blind insertion.
 */
public class SecuritySteps {

	private static final String SEC_ALICE_CARD = "4000222200008888";
	private static final String SEC_BOB_CARD = "4000222200009999";
	private static final String SEC_PIN = "1234";

	/** Seeded once per suite run (static — Spring context is shared). */
	private static volatile UUID secAliceAccountId;
	// set atomically with secAliceAccountId in the same synchronized block — always non-null after the first @Before
	private static volatile UUID secBobAccountId;

	@LocalServerPort
	private int port;

	@Autowired
	private AccountRepo accountRepo;

	@Autowired
	private CardRepo cardRepo;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private RestTestClient client;

	/** Bearer token obtained via client-credentials for the current scenario. */
	private String token;

	/** Session cookie value (NAME=VALUE only, attributes stripped). */
	private String sessionCookie;

	/** Status code captured by the most recent request. */
	private int lastStatus;

	@Before
	public void setup() {
		client = RestTestClient.bindToServer()
				.baseUrl("http://localhost:%d".formatted(port))
				.build();
		seedSecurityAccountsOnce();
	}

	/**
	 * Idempotent seeding: creates SecAlice and SecBob if their cards do not yet
	 * exist. Uses double-checked locking on the static IDs — identical to the
	 * {@code bearerToken} pattern in {@code AccountTransactionSteps}.
	 */
	private void seedSecurityAccountsOnce() {
		if (secAliceAccountId == null) {
			synchronized (SecuritySteps.class) {
				if (secAliceAccountId == null) {
					secAliceAccountId = upsertAccount("SecAlice", new BigDecimal("100000.00"), SEC_ALICE_CARD);
					secBobAccountId = upsertAccount("SecBob", new BigDecimal("500.00"), SEC_BOB_CARD);
				}
			}
		}
	}

	private UUID upsertAccount(final String holder, final BigDecimal balance, final String card) {
		return cardRepo.findByCardNumber(card)
				.map(CardEntity::getAccountId)
				.orElseGet(() -> {
					final AccountEntity account = accountRepo.save(new AccountEntity(holder, balance, "EUR"));
					cardRepo.save(new CardEntity(account.getId(), card, passwordEncoder.encode(SEC_PIN)));
					return account.getId();
				});
	}

	private UUID accountId(final String holder) {
		return switch (holder) {
			case "SecAlice" -> secAliceAccountId;
			case "SecBob" -> secBobAccountId;
			default -> throw new IllegalArgumentException("Unknown security test holder: " + holder);
		};
	}

	// ── Given steps ──────────────────────────────────────────────────────────

	@Given("a kiosk session for card {string} pin {string}")
	public void kioskSession(final String card, final String pin) {
		final var request = client.post()
				.uri("/api/v1/atm/session")
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
						{"cardNumber": "%s", "pin": "%s"}""".formatted(card, pin));
		final var result = request.exchange().returnResult(String.class);
		assertThat(result.getStatus().value())
				.as("Kiosk session bootstrap must return 200")
				.isEqualTo(200);
		final var setCookies = result.getResponseHeaders().get(HttpHeaders.SET_COOKIE);
		assertThat(setCookies)
				.as("Bootstrap must set at least one cookie")
				.isNotNull()
				.isNotEmpty();
		// Iterate all Set-Cookie headers; find JSESSIONID by name.
		// Strip attributes — keep only NAME=VALUE before the first ';'.
		for (final String raw : setCookies) {
			final String pair = raw.contains(";") ? raw.substring(0, raw.indexOf(';')) : raw;
			if (pair.startsWith("JSESSIONID=")) {
				sessionCookie = pair;
				break;
			}
		}
		assertThat(sessionCookie)
				.as("Bootstrap must set a JSESSIONID cookie")
				.isNotNull()
				.startsWith("JSESSIONID=");
	}

	@Given("a client-credentials token with scopes {string}")
	public void clientCredentialsToken(final String scopes) {
		token = TestTokens.bearer("http://localhost:%d".formatted(port), scopes);
	}

	// ── When steps ───────────────────────────────────────────────────────────

	@When("an anonymous withdrawal of {bigdecimal} is attempted on {string}")
	public void anonymousWithdrawal(final BigDecimal amount, final String holder) {
		lastStatus = withdraw(accountId(holder), amount, null, null);
	}

	@When("the session withdraws {bigdecimal} from {string}")
	public void sessionWithdraws(final BigDecimal amount, final String holder) {
		lastStatus = withdraw(accountId(holder), amount, null, sessionCookie);
	}

	@When("the session withdraws {bigdecimal} from {string} sending a garbage bearer token")
	public void sessionWithdrawsGarbageBearer(final BigDecimal amount, final String holder) {
		// Garbage bearer wins over the valid session: resource-server filter rejects first.
		lastStatus = withdraw(accountId(holder), amount, "Bearer not-a-real-token", sessionCookie);
	}

	@When("the token withdraws {bigdecimal} from {string}")
	public void tokenWithdraws(final BigDecimal amount, final String holder) {
		lastStatus = withdraw(accountId(holder), amount, token, null);
	}

	@When("the token reads the transactions of {string}")
	public void tokenReadsTransactions(final String holder) {
		assertThat(token).as("scenario must obtain a token first").isNotNull();
		final var request = client.get()
				.uri("/api/v1/accounts/{accountId}/transactions", accountId(holder))
				.header("Authorization", token);
		lastStatus = capture(request);
	}

	@When("actuator {string} is requested anonymously")
	public void actuatorAnonymous(final String endpoint) {
		final var request = client.get()
				.uri("/actuator/{endpoint}", endpoint);
		lastStatus = capture(request);
	}

	@When("actuator {string} is requested with the token")
	public void actuatorWithToken(final String endpoint) {
		assertThat(token).as("scenario must obtain a token first").isNotNull();
		final var request = client.get()
				.uri("/actuator/{endpoint}", endpoint)
				.header("Authorization", token);
		lastStatus = capture(request);
	}

	@When("the token deposits {bigdecimal} to {string}")
	public void tokenDeposits(final BigDecimal amount, final String holder) {
		assertThat(token).as("scenario must obtain a token first").isNotNull();
		lastStatus = deposit(accountId(holder), amount, token);
	}

	// ── Then steps ───────────────────────────────────────────────────────────

	@Then("the security response status is {int}")
	public void securityResponseStatus(final int expected) {
		assertThat(lastStatus).isEqualTo(expected);
	}

	// ── Private helpers ───────────────────────────────────────────────────────

	/**
	 * POST a withdrawal. Either {@code authorizationHeader} or {@code cookie} may
	 * be null (unauthenticated anonymous call sends neither).
	 */
	private int withdraw(final UUID accountId, final BigDecimal amount,
			final String authorizationHeader, final String cookie) {
		return postOperation("withdrawals", accountId, amount, authorizationHeader, cookie);
	}

	/**
	 * POST a deposit with the given bearer token.
	 */
	private int deposit(final UUID accountId, final BigDecimal amount, final String authorizationHeader) {
		return postOperation("deposits", accountId, amount, authorizationHeader, null);
	}

	/**
	 * POST to /api/v1/accounts/{accountId}/{operation} with optional auth header and cookie.
	 */
	private int postOperation(final String operation, final UUID accountId, final BigDecimal amount,
			final String authorizationHeader, final String cookie) {
		final var request = client.post()
				.uri("/api/v1/accounts/{accountId}/{operation}", accountId, operation)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON);
		if (authorizationHeader != null) {
			request.header("Authorization", authorizationHeader);
		}
		if (cookie != null) {
			request.header("Cookie", cookie);
		}
		return capture(request.body("""
				{"amount": %s}""".formatted(amount)));
	}

	private int capture(final RestTestClient.RequestHeadersSpec<?> request) {
		final var result = request.exchange().returnResult(String.class);
		return result.getStatus().value();
	}
}
