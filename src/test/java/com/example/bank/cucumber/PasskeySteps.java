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
	private String preBootstrapSessionId;
	private String bootstrappedAccountId;

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
		// Capture the accountId on every successful bootstrap so sessionSnapshotShows
		// can assert round-trip identity even when sessionEstablished() is not in the scenario.
		final String id = lastResult.body().at("/data/accountId").asString();
		if (id != null && !id.isBlank()) {
			bootstrappedAccountId = id;
		}
	}

	@When("passkey registration options are requested without a session")
	public void registrationOptionsNoSession() {
		// No ATM session, but prime the CSRF token via a public call first (CsrfCookieFilter
		// writes XSRF-TOKEN on every response) so the request clears the CsrfFilter and
		// reaches the WebAuthn options filter — which is what refuses an ANONYMOUS caller
		// with the user-enumeration-safe 400. This isolates the security property under
		// test (no challenge to the unauthenticated) from a plain CSRF 403.
		exchangePost("/webauthn/authenticate/options", null, false);
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
		bootstrappedAccountId = lastResult.body().at("/data/accountId").asString();
		assertThat(bootstrappedAccountId).isNotBlank();
		// masked card: bullets + the real last-4 only (no full PAN on the wire)
		assertThat(lastResult.body().at("/data/maskedCardNumber").asString())
				.contains("•").matches(".*\\d{4}$");
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

	// --- Fix 1: session-fixation (id rotation on privilege elevation) ---

	@When("a pre-auth ceremony call has established a kiosk session")
	public void preAuthSessionEstablished() {
		// /webauthn/authenticate/options is public and creates a JSESSIONID — exactly
		// the kind of pre-authentication session that must NOT survive bootstrap.
		exchangePost("/webauthn/authenticate/options", null, false);
		preBootstrapSessionId = cookies.get("JSESSIONID");
		assertThat(preBootstrapSessionId)
				.as("a pre-bootstrap JSESSIONID must exist to prove rotation")
				.isNotBlank();
	}

	@Then("the kiosk session id is rotated by the bootstrap")
	public void sessionIdRotated() {
		assertThat(lastResult.status()).isEqualTo(200);
		final String postBootstrapSessionId = cookies.get("JSESSIONID");
		assertThat(postBootstrapSessionId)
				.as("bootstrap must issue a NEW JSESSIONID (session-fixation defence)")
				.isNotBlank()
				.isNotEqualTo(preBootstrapSessionId);
	}

	// --- Fix 2: CSRF token priming on the exempt bootstrap response ---

	@Then("the bootstrap response carries an XSRF-TOKEN cookie")
	public void bootstrapCarriesXsrfCookie() {
		assertThat(lastResult.status()).isEqualTo(200);
		assertThat(cookies)
				.as("CsrfCookieFilter must prime the XSRF-TOKEN cookie on the exempt bootstrap")
				.containsKey("XSRF-TOKEN");
		assertThat(cookies.get("XSRF-TOKEN")).isNotBlank();
	}

	@Then("passkey registration options succeed on the first try with the primed CSRF token")
	public void registrationOptionsSucceedFirstTry() {
		// csrf=true echoes the XSRF-TOKEN cookie primed by the bootstrap response; with
		// the retry-dance removed this MUST pass first try (200), proving Fix 2.
		final HttpResult result = exchangePost("/webauthn/register/options", null, true);
		assertThat(result.status())
				.as("register/options must succeed FIRST try (no 403 retry), was %s", result.status())
				.isEqualTo(200);
		assertThat(result.body().at("/challenge").asString()).isNotBlank();
	}

	// --- Fix 3: end-session endpoint (kiosk exit) ---

	@When("the ATM session is ended")
	public void endSession() {
		lastResult = exchangePost("/api/v1/atm/session/end", null, false);
	}

	@When("the ATM session is ended with no session")
	public void endSessionNoSession() {
		// fresh client/cookies — never bootstrapped
		lastResult = exchangePost("/api/v1/atm/session/end", null, false);
	}

	@Then("the session-end returns 204")
	public void sessionEndReturns204() {
		assertThat(lastResult.status()).isEqualTo(204);
	}

	@Then("passkey registration options are refused again after the session ended")
	public void registrationRefusedAfterEnd() {
		// session is dead → the framework filter refuses with 400 and issues NO challenge
		final HttpResult result = exchangePost("/webauthn/register/options", null, true);
		assertThat(result.status())
				.as("after end, register/options must be refused (400), was %s", result.status())
				.isEqualTo(400);
		assertThat(result.body().at("/challenge").isMissingNode()).isTrue();
	}

	// --- Task 6: whoami / GET /api/v1/atm/session ---

	@When("the current session snapshot is requested")
	public void sessionSnapshotRequested() {
		lastResult = exchangeGet("/api/v1/atm/session", true);
	}

	@When("the current session snapshot is requested without a session")
	public void sessionSnapshotRequestedAnonymously() {
		lastResult = exchangeGet("/api/v1/atm/session", false);
	}

	@Then("the session snapshot shows holder {string} with balance {bigdecimal} and passkey not enrolled")
	public void sessionSnapshotShows(final String holder, final BigDecimal balance) {
		assertThat(lastResult.status()).isEqualTo(200);
		assertThat(lastResult.body().at("/data/holderName").asString()).isEqualTo(holder);
		assertThat(lastResult.body().at("/data/balance").decimalValue()).isEqualByComparingTo(balance);
		assertThat(lastResult.body().at("/data/maskedCardNumber").asString()).endsWith("7777");
		assertThat(lastResult.body().at("/data/passkeyEnrolled").asBoolean()).isFalse();
		assertThat(lastResult.body().at("/data/accountId").asString()).isEqualTo(bootstrappedAccountId);
	}

	@Then("the snapshot request is refused with status 401")
	public void snapshotRefused() {
		assertThat(lastResult.status()).isEqualTo(401);
	}

	/**
	 * One POST capturing cookies. When {@code csrf} is true the held XSRF-TOKEN cookie
	 * (primed on every prior response by {@code CsrfCookieFilter} — including the
	 * CSRF-exempt bootstrap) is echoed back in the X-XSRF-TOKEN header. There is NO
	 * retry-on-403: the token is already in hand from the bootstrap response, so a
	 * CSRF-protected ceremony POST must succeed on the FIRST try. Endpoints under
	 * /api/** and the pre-auth ceremony endpoints are CSRF-exempt.
	 */
	private HttpResult exchangePost(final String uri, final String body, final boolean csrf) {
		return doPost(uri, body, csrf ? cookies.get("XSRF-TOKEN") : null);
	}

	/** One GET capturing cookies; {@code withCookies} controls whether the held session cookie is sent. */
	private HttpResult exchangeGet(final String uri, final boolean withCookies) {
		final var request = client.get().uri(uri);
		if (withCookies) {
			applyCookies(request);
		}
		return capture(request);
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
