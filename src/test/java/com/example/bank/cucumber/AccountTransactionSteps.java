package com.example.bank.cucumber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.bank.config.CachingConfig;
import com.example.bank.jpa.model.AccountEntity;
import com.example.bank.jpa.repo.AccountRepo;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class AccountTransactionSteps {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepo accountRepo;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CacheManager cacheManager;

	private final Map<String, UUID> accountsByHolder = new HashMap<>();
	private UUID lastIdempotencyKey;
	private BigDecimal lastAmount;
	private MvcResult lastResult;
	private MvcResult previousResult;
	private List<MvcResult> parallelResults;
	private CacheStats statsBeforeFetches;

	@Given("an account for {string} with balance {bigdecimal}")
	public void anAccountWithBalance(final String holder, final BigDecimal balance) {
		final AccountEntity account = accountRepo.save(new AccountEntity(holder, balance, "EUR"));
		accountsByHolder.put(holder, account.getId());
	}

	@When("{string} withdraws {bigdecimal}")
	public void withdraws(final String holder, final BigDecimal amount) throws Exception {
		perform("withdrawals", accountsByHolder.get(holder), amount, UUID.randomUUID());
	}

	@When("{string} deposits {bigdecimal}")
	public void deposits(final String holder, final BigDecimal amount) throws Exception {
		perform("deposits", accountsByHolder.get(holder), amount, UUID.randomUUID());
	}

	@When("an unknown account withdraws {bigdecimal}")
	public void unknownAccountWithdraws(final BigDecimal amount) throws Exception {
		perform("withdrawals", UUID.randomUUID(), amount, UUID.randomUUID());
	}

	@When("{string} retries the same withdrawal with the same Idempotency-Key")
	public void retriesSameWithdrawal(final String holder) throws Exception {
		previousResult = lastResult;
		// replay the original request verbatim — same amount, same key
		perform("withdrawals", accountsByHolder.get(holder), lastAmount, lastIdempotencyKey);
	}

	@When("{string} withdraws {bigdecimal} reusing the previous Idempotency-Key")
	public void withdrawsReusingKey(final String holder, final BigDecimal amount) throws Exception {
		perform("withdrawals", accountsByHolder.get(holder), amount, lastIdempotencyKey);
	}

	@When("{string} withdraws {bigdecimal} without an Idempotency-Key")
	public void withdrawsWithoutKey(final String holder, final BigDecimal amount) throws Exception {
		lastResult = mockMvc.perform(withdrawalRequest(accountsByHolder.get(holder), amount))
				.andReturn();
	}

	@When("{string} withdraws {bigdecimal} twice in parallel")
	public void withdrawsTwiceInParallel(final String holder, final BigDecimal amount) throws Exception {
		final UUID accountId = accountsByHolder.get(holder);
		final CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			final List<java.util.concurrent.Future<MvcResult>> futures = List.of(
					executor.submit(() -> raceWithdrawal(start, accountId, amount)),
					executor.submit(() -> raceWithdrawal(start, accountId, amount)));
			start.countDown();
			parallelResults = List.of(futures.get(0).get(30, TimeUnit.SECONDS),
					futures.get(1).get(30, TimeUnit.SECONDS));
		}
	}

	@When("{string} withdraws {bigdecimal} using API version {string}")
	public void withdrawsUsingApiVersion(final String holder, final BigDecimal amount, final String version)
			throws Exception {
		lastResult = mockMvc.perform(post("/api/{version}/accounts/{accountId}/withdrawals",
						version, accountsByHolder.get(holder))
						.header("Idempotency-Key", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": %s}""".formatted(amount)))
				.andReturn();
	}

	@When("the created transaction is fetched twice")
	public void createdTransactionFetchedTwice() throws Exception {
		final JsonNode created = body(lastResult).at("/data");
		final String accountId = created.at("/accountId").asString();
		final String transactionId = created.at("/transactionId").asString();

		statsBeforeFetches = transactionCacheStats();
		for (int i = 0; i < 2; i++) {
			lastResult = mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions/{transactionId}",
							accountId, transactionId))
					.andReturn();
			assertThat(lastResult.getResponse().getStatus()).isEqualTo(200);
		}
	}

	@When("the statement of an unknown account is requested")
	public void statementOfUnknownAccount() throws Exception {
		lastResult = mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", UUID.randomUUID()))
				.andReturn();
	}

	@When("the statement of {string} is requested sorted by {string}")
	public void statementSortedBy(final String holder, final String sortProperty) throws Exception {
		// the template encodes the variable exactly like Swagger UI does (%5B%22string%22%5D)
		lastResult = mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions?sort={sort}",
						accountsByHolder.get(holder), sortProperty))
				.andReturn();
	}

	@When("the OpenAPI spec is fetched")
	public void openApiSpecFetched() throws Exception {
		lastResult = mockMvc.perform(get("/v3/api-docs")).andReturn();
		assertThat(lastResult.getResponse().getStatus()).isEqualTo(200);
	}

	@When("a transaction lookup for {string} uses an unknown transaction id")
	public void unknownTransactionLookup(final String holder) throws Exception {
		lastResult = mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions/{transactionId}",
						accountsByHolder.get(holder), UUID.randomUUID()))
				.andReturn();
	}

	@Then("the operation is created with balance after {bigdecimal}")
	public void operationCreated(final BigDecimal balanceAfter) throws Exception {
		assertThat(lastResult.getResponse().getStatus()).isEqualTo(201);
		final JsonNode body = body(lastResult);
		assertThat(body.at("/success").asBoolean()).isTrue();
		assertThat(body.at("/data/balanceAfter").decimalValue()).isEqualByComparingTo(balanceAfter);
	}

	@Then("the operation fails with status {int} and error code {string}")
	public void operationFails(final int status, final String errorCode) throws Exception {
		assertThat(lastResult.getResponse().getStatus()).isEqualTo(status);
		final JsonNode body = body(lastResult);
		assertThat(body.at("/success").asBoolean()).isFalse();
		assertThat(body.at("/error/code").asString()).isEqualTo(errorCode);
	}

	@Then("the error reports a violation on field {string}")
	public void errorReportsViolation(final String field) throws Exception {
		final JsonNode violations = body(lastResult).at("/error/violations");
		assertThat(violations.isArray()).isTrue();
		assertThat(violations.valueStream().map(v -> v.at("/field").asString())).contains(field);
	}

	@Then("both responses are created with the same transaction id")
	public void bothResponsesIdentical() throws Exception {
		assertThat(previousResult.getResponse().getStatus()).isEqualTo(201);
		assertThat(lastResult.getResponse().getStatus()).isEqualTo(201);
		final String firstTxId = body(previousResult).at("/data/transactionId").asString();
		final String secondTxId = body(lastResult).at("/data/transactionId").asString();
		assertThat(secondTxId).isEqualTo(firstTxId);
	}

	@Then("exactly one parallel withdrawal succeeds")
	public void exactlyOneSucceeds() {
		final List<Integer> statuses = parallelResults.stream()
				.map(result -> result.getResponse().getStatus())
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
	public void responseCarriesTraceId() throws Exception {
		final JsonNode traceId = body(lastResult).at("/traceId");
		// OpenTelemetry tracer active end-to-end: the envelope's traceId ties the
		// response to the server-side trace exported via OTLP (dev profile).
		assertThat(traceId.isMissingNode()).isFalse();
		assertThat(traceId.asString()).isNotBlank();
	}

	@Then("the account balance of {string} is {bigdecimal}")
	public void accountBalanceIs(final String holder, final BigDecimal expectedBalance) {
		final BigDecimal balance = accountRepo.findById(accountsByHolder.get(holder))
				.orElseThrow()
				.getBalance();
		assertThat(balance).isEqualByComparingTo(expectedBalance);
	}

	@Then("the statement of {string} shows {int} transaction(s) of type {string}")
	public void statementShowsTransactions(final String holder, final int count, final String type)
			throws Exception {
		final JsonNode content = statementContent(accountsByHolder.get(holder));
		assertThat(content.size()).isEqualTo(count);
		content.valueStream().forEach(tx -> assertThat(tx.at("/type").asString()).isEqualTo(type));
	}

	@Then("the statement of {string} shows {int} transactions")
	public void statementShowsCount(final String holder, final int count) throws Exception {
		assertThat(statementContent(accountsByHolder.get(holder)).size()).isEqualTo(count);
	}

	@Then("the statement response lists {int} transaction(s)")
	public void statementResponseListsTransactions(final int count) throws Exception {
		assertThat(lastResult.getResponse().getStatus()).isEqualTo(200);
		assertThat(body(lastResult).at("/data/content").size()).isEqualTo(count);
	}

	@Then("the statement response lists {int} transactions with amounts in ascending order")
	public void statementResponseAmountsAscending(final int count) throws Exception {
		statementResponseListsTransactions(count);
		final List<BigDecimal> amounts = body(lastResult).at("/data/content").valueStream()
				.map(tx -> tx.at("/amount").decimalValue())
				.toList();
		assertThat(amounts).isSorted();
	}

	@Then("every documented path starts with {string} and carries no version placeholder")
	public void documentedPathsCarryConcreteVersion(final String prefix) throws Exception {
		final var documentedPaths = body(lastResult).at("/paths").propertyNames();
		assertThat(documentedPaths).isNotEmpty();
		assertThat(documentedPaths).allSatisfy(path ->
				assertThat(path).startsWith(prefix).doesNotContain("{api-version}"));
	}

	@Then("the statement of {string} lists {int} transactions newest first")
	public void statementNewestFirst(final String holder, final int count) throws Exception {
		final JsonNode content = statementContent(accountsByHolder.get(holder));
		assertThat(content.size()).isEqualTo(count);
		final List<Instant> timestamps = content.valueStream()
				.map(tx -> Instant.parse(tx.at("/occurredAt").asString()))
				.toList();
		assertThat(timestamps).isSortedAccordingTo(java.util.Comparator.reverseOrder());
	}

	private MvcResult raceWithdrawal(final CountDownLatch start, final UUID accountId, final BigDecimal amount) {
		try {
			start.await(10, TimeUnit.SECONDS);
			return mockMvc.perform(withdrawalRequest(accountId, amount)
							.header("Idempotency-Key", UUID.randomUUID()))
					.andReturn();
		} catch (final Exception e) {
			throw new IllegalStateException("Parallel withdrawal failed", e);
		}
	}

	private void perform(final String operation, final UUID accountId, final BigDecimal amount,
			final UUID idempotencyKey) throws Exception {
		lastIdempotencyKey = idempotencyKey;
		lastAmount = amount;
		lastResult = mockMvc.perform(post("/api/v1/accounts/{accountId}/{operation}", accountId, operation)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": %s}""".formatted(amount)))
				.andReturn();
	}

	private MockHttpServletRequestBuilder withdrawalRequest(final UUID accountId, final BigDecimal amount) {
		return post("/api/v1/accounts/{accountId}/withdrawals", accountId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"amount": %s}""".formatted(amount));
	}

	private JsonNode statementContent(final UUID accountId) throws Exception {
		final MvcResult result = mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", accountId))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return body(result).at("/data/content");
	}

	private JsonNode body(final MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private CacheStats transactionCacheStats() {
		final CaffeineCache cache = (CaffeineCache) cacheManager.getCache(CachingConfig.TRANSACTION_BY_ID);
		return cache.getNativeCache().stats();
	}
}
