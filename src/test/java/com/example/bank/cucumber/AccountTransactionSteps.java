package com.example.bank.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.example.bank.config.CachingConfig;
import com.example.bank.jdbc.model.AccountEntity;
import com.example.bank.jdbc.model.CardEntity;
import com.example.bank.jdbc.repo.AccountRepo;
import com.example.bank.jdbc.repo.CardRepo;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Glue is scenario-scoped (fresh instance per scenario), but the Spring context,
 * database and Caffeine cache are shared across the whole suite run. ISOLATION
 * INVARIANT: every assertion must stay scoped to this scenario's own account
 * UUIDs (and cache assertions must use stats DELTAS, never absolute counts) —
 * the ledger and idempotency tables accumulate rows across scenarios by design.
 */
public class AccountTransactionSteps {

	/** Captured wire-level response: status code + parsed JSON body. */
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

	@Autowired
	private CacheManager cacheManager;

	private RestTestClient client;

	private final Map<String, UUID> accountsByHolder = new HashMap<>();
	private UUID lastIdempotencyKey;
	private BigDecimal lastAmount;
	private HttpResult lastResult;
	private HttpResult previousResult;
	private List<HttpResult> parallelResults;
	private CacheStats statsBeforeFetches;
	private String sentTraceId;
	private String acceptLanguage;

	@Before
	public void bindClient() {
		client = RestTestClient.bindToServer()
				.baseUrl("http://localhost:%d".formatted(port))
				.build();
	}

	@Given("the client speaks {string}")
	public void clientSpeaks(final String languageTag) {
		acceptLanguage = languageTag;
	}

	@Given("an account for {string} with balance {bigdecimal}")
	public void anAccountWithBalance(final String holder, final BigDecimal balance) {
		final AccountEntity account = accountRepo.save(new AccountEntity(holder, balance, "EUR"));
		accountsByHolder.put(holder, account.getId());
	}

	@Given("an account for {string} with balance {bigdecimal} and card {string} pin {string}")
	public void accountWithCardAndPin(final String holder, final BigDecimal balance,
			final String card, final String pin) {
		final AccountEntity account = accountRepo.save(new AccountEntity(holder, balance, "EUR"));
		accountsByHolder.put(holder, account.getId());
		cardRepo.save(new CardEntity(account.getId(), card, passwordEncoder.encode(pin)));
	}

	@When("the card {string} is looked up")
	public void cardLookedUp(final String card) {
		lastResult = get("/api/v1/cards/{cardNumber}", card);
	}

	@When("the card {string} is authenticated with pin {string}")
	public void cardAuthenticated(final String card, final String pin) {
		final var request = client.post().uri("/api/v1/cards/{cardNumber}/pin", card)
				.contentType(MediaType.APPLICATION_JSON);
		if (acceptLanguage != null) {
			request.header("Accept-Language", acceptLanguage);
		}
		lastResult = capture(request.body("""
				{"pin": "%s"}""".formatted(pin)));
	}

	@Then("the greeting shows holder {string} and masked card {string} with no balance")
	public void greetingShows(final String holder, final String masked) {
		assertThat(lastResult.status()).isEqualTo(200);
		assertThat(lastResult.body().at("/data/holderName").asString()).isEqualTo(holder);
		assertThat(lastResult.body().at("/data/maskedCardNumber").asString()).isEqualTo(masked);
		assertThat(lastResult.body().at("/data/balance").isMissingNode()).isTrue();
	}

	@Then("the authenticated snapshot shows balance {bigdecimal} for holder {string}")
	public void authSnapshot(final BigDecimal balance, final String holder) {
		assertThat(lastResult.status()).isEqualTo(200);
		assertThat(lastResult.body().at("/data/holderName").asString()).isEqualTo(holder);
		assertThat(lastResult.body().at("/data/balance").decimalValue()).isEqualByComparingTo(balance);
		assertThat(lastResult.body().at("/data/accountId").asString()).isNotBlank();
	}

	@When("{string} withdraws {bigdecimal}")
	public void withdraws(final String holder, final BigDecimal amount) {
		perform("withdrawals", accountsByHolder.get(holder), amount, UUID.randomUUID());
	}

	@When("{string} deposits {bigdecimal}")
	public void deposits(final String holder, final BigDecimal amount) {
		perform("deposits", accountsByHolder.get(holder), amount, UUID.randomUUID());
	}

	@When("an unknown account withdraws {bigdecimal}")
	public void unknownAccountWithdraws(final BigDecimal amount) {
		perform("withdrawals", UUID.randomUUID(), amount, UUID.randomUUID());
	}

	@When("{string} retries the same withdrawal with the same Idempotency-Key")
	public void retriesSameWithdrawal(final String holder) {
		previousResult = lastResult;
		// replay the original request verbatim — same amount, same key
		perform("withdrawals", accountsByHolder.get(holder), lastAmount, lastIdempotencyKey);
	}

	@When("{string} withdraws {bigdecimal} reusing the previous Idempotency-Key")
	public void withdrawsReusingKey(final String holder, final BigDecimal amount) {
		perform("withdrawals", accountsByHolder.get(holder), amount, lastIdempotencyKey);
	}

	@When("{string} withdraws {bigdecimal} without an Idempotency-Key")
	public void withdrawsWithoutKey(final String holder, final BigDecimal amount) {
		lastResult = exchange("withdrawals", accountsByHolder.get(holder), amount, null, null);
	}

	@When("{string} withdraws {bigdecimal} twice in parallel")
	public void withdrawsTwiceInParallel(final String holder, final BigDecimal amount) throws Exception {
		final UUID accountId = accountsByHolder.get(holder);
		final CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			final List<Future<HttpResult>> futures = List.of(
					executor.submit(() -> raceWithdrawal(start, accountId, amount)),
					executor.submit(() -> raceWithdrawal(start, accountId, amount)));
			start.countDown();
			parallelResults = List.of(futures.get(0).get(30, TimeUnit.SECONDS),
					futures.get(1).get(30, TimeUnit.SECONDS));
		}
	}

	@When("{string} withdraws {bigdecimal} using API version {string}")
	public void withdrawsUsingApiVersion(final String holder, final BigDecimal amount, final String version) {
		lastResult = capture(client.post()
				.uri("/api/{version}/accounts/{accountId}/withdrawals", version, accountsByHolder.get(holder))
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
						{"amount": %s}""".formatted(amount)));
	}

	@When("{string} withdraws {bigdecimal} sending a client traceparent")
	public void withdrawsSendingClientTraceparent(final String holder, final BigDecimal amount) {
		sentTraceId = UUID.randomUUID().toString().replace("-", "");
		final String spanId = sentTraceId.substring(0, 16);
		lastResult = exchange("withdrawals", accountsByHolder.get(holder), amount, UUID.randomUUID(),
				"00-%s-%s-01".formatted(sentTraceId, spanId));
	}

	@When("the created transaction is fetched twice")
	public void createdTransactionFetchedTwice() {
		final JsonNode created = lastResult.body().at("/data");
		final String accountId = created.at("/accountId").asString();
		final String transactionId = created.at("/transactionId").asString();

		statsBeforeFetches = transactionCacheStats();
		for (int i = 0; i < 2; i++) {
			lastResult = get("/api/v1/accounts/{accountId}/transactions/{transactionId}",
					accountId, transactionId);
			assertThat(lastResult.status()).isEqualTo(200);
		}
	}

	@When("the statement of an unknown account is requested")
	public void statementOfUnknownAccount() {
		lastResult = get("/api/v1/accounts/{accountId}/transactions", UUID.randomUUID());
	}

	@When("the statement of {string} is requested sorted by {string}")
	public void statementSortedBy(final String holder, final String sortProperty) {
		// the template encodes the variable exactly like Swagger UI does (%5B%22string%22%5D)
		lastResult = get("/api/v1/accounts/{accountId}/transactions?sort={sort}",
				accountsByHolder.get(holder), sortProperty);
	}

	@When("the statement of {string} is requested with the raw unencoded sort [\"type\"]")
	public void statementWithRawUnencodedSort(final String holder) throws Exception {
		// java.net.URI cannot legally carry unencoded [ or " in a query, so the
		// request goes over a raw socket — exactly the bytes a sloppy client sends
		lastResult = rawGet("/api/v1/accounts/%s/transactions?sort=[\"type\"]"
				.formatted(accountsByHolder.get(holder)));
	}

	@When("a transaction lookup for {string} uses an unknown transaction id")
	public void unknownTransactionLookup(final String holder) {
		lastResult = get("/api/v1/accounts/{accountId}/transactions/{transactionId}",
				accountsByHolder.get(holder), UUID.randomUUID());
	}

	@Then("the operation is created with balance after {bigdecimal}")
	public void operationCreated(final BigDecimal balanceAfter) {
		assertThat(lastResult.status()).isEqualTo(201);
		assertThat(lastResult.body().at("/success").asBoolean()).isTrue();
		assertThat(lastResult.body().at("/data/balanceAfter").decimalValue())
				.isEqualByComparingTo(balanceAfter);
	}

	@Then("the operation fails with status {int} and error code {string}")
	public void operationFails(final int status, final String errorCode) {
		assertThat(lastResult.status()).isEqualTo(status);
		assertThat(lastResult.body().at("/success").asBoolean()).isFalse();
		assertThat(lastResult.body().at("/error/code").asString()).isEqualTo(errorCode);
	}

	@Then("the error message is {string}")
	public void errorMessageIs(final String message) {
		assertThat(lastResult.body().at("/error/message").asString()).isEqualTo(message);
	}

	@Then("the error message contains {string}")
	public void errorMessageContains(final String fragment) {
		assertThat(lastResult.body().at("/error/message").asString()).contains(fragment);
	}

	@Then("the error reports a violation on field {string}")
	public void errorReportsViolation(final String field) {
		final JsonNode violations = lastResult.body().at("/error/violations");
		assertThat(violations.isArray()).isTrue();
		assertThat(violations.valueStream().map(v -> v.at("/field").asString())).contains(field);
	}

	@Then("the violation on field {string} has code {string} and message {string} and rejected value {string}")
	public void violationDetails(final String field, final String code, final String message,
			final String rejectedValue) {
		final JsonNode violation = violationOn(field);
		assertThat(violation.at("/code").asString()).isEqualTo(code);
		assertThat(violation.at("/message").asString()).isEqualTo(message);
		assertThat(violation.at("/rejectedValue").asString()).isEqualTo(rejectedValue);
	}

	@Then("the violation on field {string} has code {string} and message {string}")
	public void violationCodeAndMessage(final String field, final String code, final String message) {
		final JsonNode violation = violationOn(field);
		assertThat(violation.at("/code").asString()).isEqualTo(code);
		assertThat(violation.at("/message").asString()).isEqualTo(message);
	}

	private JsonNode violationOn(final String field) {
		return lastResult.body().at("/error/violations").valueStream()
				.filter(violation -> field.equals(violation.at("/field").asString()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no violation on field %s".formatted(field)));
	}

	@Then("both responses are created with the same transaction id")
	public void bothResponsesIdentical() {
		assertThat(previousResult.status()).isEqualTo(201);
		assertThat(lastResult.status()).isEqualTo(201);
		final String firstTxId = previousResult.body().at("/data/transactionId").asString();
		final String secondTxId = lastResult.body().at("/data/transactionId").asString();
		assertThat(secondTxId).isEqualTo(firstTxId);
	}

	@Then("exactly one parallel withdrawal succeeds")
	public void exactlyOneSucceeds() {
		final List<Integer> statuses = parallelResults.stream()
				.map(HttpResult::status)
				.sorted()
				.toList();
		assertThat(statuses).containsExactly(201, 422);
	}

	@Then("the second fetch is served from the cache")
	public void secondFetchServedFromCache() {
		final CacheStats delta = transactionCacheStats().minus(statsBeforeFetches);
		// first read loads (one miss), the repeat is answered by Caffeine
		assertThat(delta.missCount()).isEqualTo(1);
		assertThat(delta.hitCount()).isGreaterThanOrEqualTo(1);
	}

	@Then("the response carries a trace id")
	public void responseCarriesTraceId() {
		final JsonNode traceId = lastResult.body().at("/traceId");
		// real HTTP end-to-end: the envelope's traceId comes from the server-side span
		assertThat(traceId.isMissingNode()).isFalse();
		assertThat(traceId.asString()).isNotBlank();
	}

	@Then("the envelope trace id equals the client-sent trace id")
	public void envelopeTraceIdEqualsClientTraceId() {
		// proves W3C traceparent extraction by the embedded server — impossible under MockMvc
		assertThat(lastResult.body().at("/traceId").asString()).isEqualTo(sentTraceId);
	}

	@When("the OpenAPI spec is fetched")
	public void openApiSpecFetched() {
		lastResult = get("/v3/api-docs");
		assertThat(lastResult.status()).isEqualTo(200);
	}

	@Then("every documented operation declares an {string} header parameter")
	public void documentedOperationsDeclareHeader(final String headerName) {
		final JsonNode paths = lastResult.body().at("/paths");
		assertThat(paths.size()).isGreaterThan(0);
		// assumes path items carry only HTTP-method entries — springdoc emits no
		// path-level summary/parameters fields unless explicitly configured
		paths.valueStream().forEach(pathItem -> pathItem.valueStream().forEach(operation ->
				assertThat(operation.at("/parameters").valueStream()
						.anyMatch(parameter -> headerName.equals(parameter.at("/name").asString())
								&& "header".equals(parameter.at("/in").asString())))
						.as("operation must document the %s header", headerName)
						.isTrue()));
	}

	@Then("every documented path starts with {string} and carries no version placeholder")
	public void documentedPathsCarryConcreteVersion(final String prefix) {
		final var documentedPaths = lastResult.body().at("/paths").propertyNames();
		assertThat(documentedPaths).isNotEmpty();
		assertThat(documentedPaths).allSatisfy(path ->
				assertThat(path).startsWith(prefix).doesNotContain("{api-version}"));
	}

	@Then("the account balance of {string} is {bigdecimal}")
	public void accountBalanceIs(final String holder, final BigDecimal expectedBalance) {
		final BigDecimal balance = accountRepo.findById(accountsByHolder.get(holder))
				.orElseThrow()
				.getBalance();
		assertThat(balance).isEqualByComparingTo(expectedBalance);
	}

	@Then("the statement of {string} shows {int} transaction(s) of type {string}")
	public void statementShowsTransactions(final String holder, final int count, final String type) {
		final JsonNode content = statementContent(accountsByHolder.get(holder));
		assertThat(content.size()).isEqualTo(count);
		content.valueStream().forEach(tx -> assertThat(tx.at("/type").asString()).isEqualTo(type));
	}

	@Then("the statement of {string} shows {int} transactions")
	public void statementShowsCount(final String holder, final int count) {
		assertThat(statementContent(accountsByHolder.get(holder)).size()).isEqualTo(count);
	}

	@Then("the statement response lists {int} transaction(s)")
	public void statementResponseListsTransactions(final int count) {
		assertThat(lastResult.status()).isEqualTo(200);
		assertThat(lastResult.body().at("/data/content").size()).isEqualTo(count);
	}

	@Then("the statement response lists {int} transactions with amounts in ascending order")
	public void statementResponseAmountsAscending(final int count) {
		statementResponseListsTransactions(count);
		final List<BigDecimal> amounts = lastResult.body().at("/data/content").valueStream()
				.map(tx -> tx.at("/amount").decimalValue())
				.toList();
		assertThat(amounts).isSorted();
	}

	@Then("the statement of {string} lists {int} transactions newest first")
	public void statementNewestFirst(final String holder, final int count) {
		final JsonNode content = statementContent(accountsByHolder.get(holder));
		assertThat(content.size()).isEqualTo(count);
		final List<Instant> timestamps = content.valueStream()
				.map(tx -> Instant.parse(tx.at("/occurredAt").asString()))
				.toList();
		assertThat(timestamps).isSortedAccordingTo(Comparator.reverseOrder());
	}

	private HttpResult raceWithdrawal(final CountDownLatch start, final UUID accountId, final BigDecimal amount) {
		try {
			start.await(10, TimeUnit.SECONDS);
			// bypass perform(): no shared step-state mutation from racing threads
			return exchange("withdrawals", accountId, amount, UUID.randomUUID(), null);
		} catch (final Exception e) {
			throw new IllegalStateException("Parallel withdrawal failed", e);
		}
	}

	/** Records the request facts for replay steps, then executes it. */
	private void perform(final String operation, final UUID accountId, final BigDecimal amount,
			final UUID idempotencyKey) {
		lastIdempotencyKey = idempotencyKey;
		lastAmount = amount;
		lastResult = exchange(operation, accountId, amount, idempotencyKey, null);
	}

	/** One POST over real HTTP; key and traceparent headers are optional. */
	private HttpResult exchange(final String operation, final UUID accountId, final BigDecimal amount,
			final UUID idempotencyKey, final String traceparent) {
		final var request = client.post()
				.uri("/api/v1/accounts/{accountId}/{operation}", accountId, operation)
				.contentType(MediaType.APPLICATION_JSON);
		if (acceptLanguage != null) {
			request.header("Accept-Language", acceptLanguage);
		}
		if (idempotencyKey != null) {
			request.header("Idempotency-Key", idempotencyKey.toString());
		}
		if (traceparent != null) {
			request.header("traceparent", traceparent);
		}
		return capture(request.body("""
				{"amount": %s}""".formatted(amount)));
	}

	/** Wire-level GET bypassing all client-side URI validation/encoding. */
	private HttpResult rawGet(final String rawPathAndQuery) throws IOException {
		try (Socket socket = new Socket("localhost", port)) {
			socket.getOutputStream().write(
					"GET %s HTTP/1.1\r\nHost: localhost:%d\r\nAccept: */*\r\nConnection: close\r\n\r\n"
							.formatted(rawPathAndQuery, port)
							.getBytes(StandardCharsets.US_ASCII));
			final String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			final int status = Integer.parseInt(response.split(" ", 3)[1]);
			final String body = response.substring(response.indexOf("\r\n\r\n") + 4);
			return new HttpResult(status, body.isBlank() || !body.startsWith("{")
					? objectMapper.createObjectNode()
					: objectMapper.readTree(body));
		}
	}

	private HttpResult get(final String uriTemplate, final Object... uriVariables) {
		final var request = client.get().uri(uriTemplate, uriVariables);
		if (acceptLanguage != null) {
			request.header("Accept-Language", acceptLanguage);
		}
		return capture(request);
	}

	private HttpResult capture(final RestTestClient.RequestHeadersSpec<?> request) {
		final var result = request.exchange().returnResult(String.class);
		final String body = result.getResponseBody();
		return new HttpResult(result.getStatus().value(),
				body == null || body.isBlank()
						? objectMapper.createObjectNode()
						: objectMapper.readTree(body));
	}

	private JsonNode statementContent(final UUID accountId) {
		final HttpResult result = get("/api/v1/accounts/{accountId}/transactions", accountId);
		assertThat(result.status()).isEqualTo(200);
		return result.body().at("/data/content");
	}

	private CacheStats transactionCacheStats() {
		final CaffeineCache cache = (CaffeineCache) cacheManager.getCache(CachingConfig.TRANSACTION_BY_ID);
		return cache.getNativeCache().stats();
	}
}
