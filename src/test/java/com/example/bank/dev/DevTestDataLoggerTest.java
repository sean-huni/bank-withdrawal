package com.example.bank.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.env.MockEnvironment;

import com.example.bank.config.properties.SecurityProperties;
import com.example.bank.domain.TransactionType;
import com.example.bank.data.model.AccountEntity;
import com.example.bank.data.model.CardEntity;
import com.example.bank.data.model.TransactionEntity;
import com.example.bank.data.repo.AccountRepo;
import com.example.bank.data.repo.CardRepo;
import com.example.bank.data.repo.TransactionRepo;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The dev banner is console output, not an HTTP flow — asserted straight off
 * the Logback appender instead of via a Cucumber scenario (the Cucumber suite
 * runs without the {@code dev} profile, where this component must not exist).
 */
class DevTestDataLoggerTest {

	private final AccountRepo accountRepo = mock(AccountRepo.class);
	private final CardRepo cardRepo = mock(CardRepo.class);
	private final TransactionRepo transactionRepo = mock(TransactionRepo.class);
	private final MockEnvironment environment = new MockEnvironment()
			.withProperty("local.server.port", "8080");

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final Logger logger = (Logger) LoggerFactory.getLogger(DevTestDataLogger.class);

	private final SecurityProperties security =
			new SecurityProperties("operator", "atm-demo", "atm-ops-secret");

	private final DevTestDataLogger devTestDataLogger =
			new DevTestDataLogger(accountRepo, cardRepo, transactionRepo, environment, security);

	@BeforeEach
	void attachAppender() {
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		logger.detachAppender(appender);
	}

	@Test
	void bannerListsSwaggerUrlSortablePropertiesAccountsAndTransactions() {
		final AccountEntity alice = account("Alice", "1000.0000");
		final AccountEntity bob = account("Bob", "250.5000");
		when(accountRepo.findAll()).thenReturn(List.of(alice, bob));
		when(cardRepo.findAll()).thenReturn(List.of(
				card(alice.getId(), "4539148803436467"),
				card(bob.getId(), "6011000990139424")));

		final TransactionEntity debit = TransactionEntity.create(
				alice.getId(), TransactionType.DEBIT,
				new BigDecimal("50.0000"), new BigDecimal("950.0000"), UUID.randomUUID());
		debit.assignIdIfMissing();
		when(transactionRepo.findByAccountId(eq(alice.getId()), any()))
				.thenReturn(new PageImpl<>(List.of(debit)));
		when(transactionRepo.findByAccountId(eq(bob.getId()), any()))
				.thenReturn(new PageImpl<>(List.of()));

		devTestDataLogger.logTestData();

		final String banner = loggedText();
		assertThat(banner)
				.contains("http://localhost:8080/swagger-ui.html")
				.contains("http://localhost:8080/v3/api-docs")
				.contains("/api/v1/accounts/{accountId}")
				// whitelist is read from the controller's @AllowedSortProperties — must match it
				.contains("createdAt, amount, type, balanceAfter")
				.contains("Idempotency-Key")
				.contains("Accept-Language")
				.contains("en, sn")
				// observability links — defaults match the compose.yml LGTM container
				.contains("Grafana").contains("http://localhost:3000")
				.contains("Prometheus").contains("http://localhost:9090")
				.contains(alice.getId().toString())
				.contains("Alice").contains("1000.0000")
				.contains(bob.getId().toString())
				.contains("Bob").contains("250.5000")
				.contains(debit.getId().toString())
				.contains("DEBIT").contains("950.0000")
				// greeting + PIN-verify curl examples in the banner header
				.contains("Card greeting")
				.contains("PIN verify")
				// ATM frontend section — cards now sourced from CardRepo, joined to holders
				.contains("ATM cards")
				.contains("Alice -> 4539148803436467")
				.contains("Bob -> 6011000990139424")
				.contains("ATM PIN").contains("1234")
				.contains("ATM frontend").contains("http://localhost:5173")
				.contains("ATM repo").contains("github.com/sean-huni/fe-bank-withdrawal")
				.contains("ATM launch").contains("npm install").contains("npm run dev")
				// passkey-enabled ATM section — rpId/origins from the same env keys the config binds
				.contains("Passkey rpId").contains("localhost").contains("http://localhost:5173")
				.contains("ATM session").contains("/api/v1/atm/session")
				.contains("Passkey enrol").contains("/webauthn/register/options")
				.contains("Passkey login").contains("/login/webauthn");
	}

	@Test
	void bannerWarnsWhenNoAccountsAreSeeded() {
		when(accountRepo.findAll()).thenReturn(List.of());

		devTestDataLogger.logTestData();

		assertThat(loggedText()).contains("No accounts found");
	}

	@Test
	void bannerPrintsDemoOAuth2CredentialAndTokenCurl() {
		when(accountRepo.findAll()).thenReturn(List.of());

		devTestDataLogger.logTestData();

		assertThat(loggedText())
				.contains("OAuth2 client")
				.contains("atm-ops")
				.contains("atm-ops-secret")
				.contains("http://localhost:8080/oauth2/token")
				.contains("grant_type=client_credentials")
				.contains("scope=atm.read atm.write")
				.doesNotContain("value redacted");
	}

	@Test
	void bannerRedactsAnOverriddenOAuth2Secret() {
		final DevTestDataLogger overriddenLogger = new DevTestDataLogger(
				accountRepo, cardRepo, transactionRepo, environment,
				new SecurityProperties("operator", "atm-demo", "real-secret-from-env"));
		when(accountRepo.findAll()).thenReturn(List.of());

		overriddenLogger.logTestData();

		assertThat(loggedText())
				.contains("ATM_OPS_CLIENT_SECRET is overridden — value redacted")
				.contains("curl -u atm-ops:$ATM_OPS_CLIENT_SECRET")
				.doesNotContain("real-secret-from-env");
	}

	// Drift in either direction fails SAFE: a changed yml default breaks this test; a changed
	// constant makes the banner redact (never leak). application-dev.yml carries no secret defaults.
	@Test
	void demoSecretConstantMatchesTheCommittedYmlDefault() throws IOException {
		final String yml = Files.readString(Path.of("src/main/resources/application.yml"));

		assertThat(yml).contains("${ATM_OPS_CLIENT_SECRET:%s}".formatted(DevTestDataLogger.DEMO_OPS_SECRET));
	}

	private AccountEntity account(final String holder, final String balance) {
		final AccountEntity account = new AccountEntity(holder, new BigDecimal(balance), "EUR");
		account.assignIdIfMissing();
		return account;
	}

	private CardEntity card(final UUID accountId, final String cardNumber) {
		final CardEntity card = new CardEntity(accountId, cardNumber, "$2a$10$irrelevant-for-banner");
		card.assignIdIfMissing();
		return card;
	}

	private String loggedText() {
		return appender.list.stream()
				.map(ILoggingEvent::getFormattedMessage)
				.collect(Collectors.joining("\n"));
	}
}
