package com.example.bank.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-HTTP wiring tests for the passkey-enabled ATM. Cookies are captured from
 * {@code Set-Cookie} and replayed so the kiosk SESSION carries across calls; the
 * non-httpOnly {@code XSRF-TOKEN} cookie is echoed back as the {@code X-XSRF-TOKEN}
 * header on the (non-/api) ceremony POSTs (the existing /api contract is CSRF-exempt).
 *
 * <p>LIMITATION: the full register/login ceremony (attestation + assertion with a
 * real authenticator) cannot run end-to-end without a virtual authenticator (e.g.
 * a headless Chrome WebAuthn virtual authenticator under Playwright). These
 * scenarios verify the framework WIRING only — endpoints exist, are protected
 * correctly, and emit well-formed challenge JSON — which is the level a JVM
 * integration test can prove. The actual signature verification is the
 * framework's (webauthn4j) and is exercised by Spring Security's own test suite.
 */
public class PasskeySteps {

	private record HttpResult(int status, JsonNode body) {
	}

	@LocalServerPort
	private int port;

	@Autowired
	private AccountRepo accountRepo;

	@Autowired
	private CardRepo cardRepo;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private ObjectMapper objectMapper;

	private RestTestClient client;
	private final Map<String, String> cookies = new LinkedHashMap<>();
	private HttpResult lastResult;

	@Before
	public void bindClient() {
		client = RestTestClient.bindToServer()
				.baseUrl("http://localhost:%d".formatted(port))
				.build();
	}

	@Given("a passkey-test account for {string} with balance {bigdecimal} and card {string} pin {string}")
	public void account(final String holder, final BigDecimal balance, final String card, final String pin) {
		final AccountEntity acc = accountRepo.save(new AccountEntity(holder, balance, "EUR"));
		cardRepo.save(new CardEntity(acc.getId(), card, passwordEncoder.encode(pin)));
	}

	@When("an ATM session is bootstrapped with card {string} and pin {string}")
	public void bootstrap(final String card, final String pin) {
		lastResult = exchangePost("/api/v1/atm/session", """
				{"cardNumber": "%s", "pin": "%s"}""".formatted(card, pin), false);
	}

	@When("passkey registration options are requested without a session")
	public void registrationOptionsNoSession() {
		// fresh client/cookies — no ATM session established
		lastResult = exchangePost("/webauthn/register/options", null, true);
	}

	@When("passkey authentication options are requested without a session")
	public void authenticationOptionsNoSession() {
		lastResult = exchangePost("/webauthn/authenticate/options", null, true);
	}

	@Then("the ATM session is established for that account with passkey not yet enrolled")
	public void sessionEstablished() {
		assertThat(lastResult.status()).isEqualTo(200);
		assertThat(lastResult.body().at("/success").asBoolean()).isTrue();
		assertThat(lastResult.body().at("/data/accountId").asString()).isNotBlank();
		assertThat(lastResult.body().at("/data/maskedCardNumber").asString()).contains("2222");
		assertThat(lastResult.body().at("/data/passkeyEnrolled").asBoolean()).isFalse();
		// a session cookie must have been issued for the follow-up ceremony (servlet default: JSESSIONID)
		assertThat(cookies).containsKey("JSESSIONID");
	}

	@Then("passkey registration options can be requested and return a challenge")
	public void registrationOptionsReachable() {
		// same client/cookies as the bootstrap → the session authenticates the ceremony
		final HttpResult result = exchangePost("/webauthn/register/options", null, true);
		assertThat(result.status()).isEqualTo(200);
		assertThat(result.body().at("/challenge").asString()).isNotBlank();
		// the relying party echoes our configured rpId/rpName
		assertThat(result.body().at("/rp/id").asString()).isEqualTo("localhost");
		assertThat(result.body().at("/user/id").isMissingNode()).isFalse();
	}

	@Then("the ATM bootstrap fails with status {int} and error code {string}")
	public void bootstrapFails(final int status, final String errorCode) {
		assertThat(lastResult.status()).isEqualTo(status);
		assertThat(lastResult.body().at("/success").asBoolean()).isFalse();
		assertThat(lastResult.body().at("/error/code").asString()).isEqualTo(errorCode);
	}

	@Then("the registration ceremony is refused without an authenticated session")
	public void registrationRefusedWithoutSession() {
		// VERIFIED against the framework source: PublicKeyCredentialCreationOptionsFilter
		// sits before the authorization filter and, finding no non-anonymous Authentication,
		// short-circuits with 400 Bad Request (user-enumeration-safe — it never reveals whether
		// a credential/user exists). So an unauthenticated enrolment attempt cannot mint a
		// challenge: the negative path is a 400, not a 200.
		assertThat(lastResult.status())
				.as("register/options without a session must be refused (400), was %s", lastResult.status())
				.isEqualTo(400);
		// the critical security property: NO challenge is issued to an anonymous caller
		assertThat(lastResult.body().at("/challenge").isMissingNode()).isTrue();
	}

	@Then("a well-formed passkey challenge is returned")
	public void wellFormedChallenge() {
		assertThat(lastResult.status()).isEqualTo(200);
		assertThat(lastResult.body().at("/challenge").asString()).isNotBlank();
		assertThat(lastResult.body().at("/rpId").asString()).isEqualTo("localhost");
	}

	/**
	 * One POST capturing cookies. When {@code csrf} is true and no XSRF-TOKEN cookie
	 * is held yet, the first POST to a CSRF-protected endpoint is rejected 403 but
	 * the CookieCsrfTokenRepository writes the XSRF-TOKEN cookie on that response
	 * (the unsafe-method path generates + saves the deferred token). The request is
	 * then retried with that token echoed in the X-XSRF-TOKEN header — exactly the
	 * fetch-then-submit dance a real SPA performs. Endpoints under /api/** and the
	 * pre-auth ceremony endpoints are CSRF-exempt, so {@code csrf} is false for those.
	 */
	private HttpResult exchangePost(final String uri, final String body, final boolean csrf) {
		final HttpResult first = doPost(uri, body, csrf ? cookies.get("XSRF-TOKEN") : null);
		if (csrf && first.status() == 403 && cookies.containsKey("XSRF-TOKEN")) {
			return doPost(uri, body, cookies.get("XSRF-TOKEN"));
		}
		return first;
	}

	private HttpResult doPost(final String uri, final String body, final String csrfToken) {
		final var request = client.post().uri(uri).contentType(MediaType.APPLICATION_JSON);
		applyCookies(request);
		if (csrfToken != null) {
			request.header("X-XSRF-TOKEN", csrfToken);
		}
		if (body != null) {
			request.body(body);
		}
		return capture(request);
	}

	private void applyCookies(final RestTestClient.RequestHeadersSpec<?> request) {
		if (!cookies.isEmpty()) {
			final String header = cookies.entrySet().stream()
					.map(e -> "%s=%s".formatted(e.getKey(), e.getValue()))
					.reduce((a, b) -> "%s; %s".formatted(a, b))
					.orElse("");
			request.header(HttpHeaders.COOKIE, header);
		}
	}

	private HttpResult capture(final RestTestClient.RequestHeadersSpec<?> request) {
		final var result = request.exchange().returnResult(String.class);
		final List<String> setCookies = result.getResponseHeaders().get(HttpHeaders.SET_COOKIE);
		if (setCookies != null) {
			for (final String setCookie : setCookies) {
				final String pair = setCookie.split(";", 2)[0];
				final int eq = pair.indexOf('=');
				if (eq > 0) {
					cookies.put(pair.substring(0, eq), pair.substring(eq + 1));
				}
			}
		}
		final String responseBody = result.getResponseBody();
		return new HttpResult(result.getStatus().value(),
				responseBody == null || responseBody.isBlank() || !responseBody.trim().startsWith("{")
						? objectMapper.createObjectNode()
						: objectMapper.readTree(responseBody));
	}
}
