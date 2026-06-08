package com.example.bank.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.env.MockEnvironment;

import com.example.bank.domain.TransactionType;
import com.example.bank.jdbc.model.AccountEntity;
import com.example.bank.jdbc.model.TransactionEntity;
import com.example.bank.jdbc.repo.AccountRepo;
import com.example.bank.jdbc.repo.TransactionRepo;

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
	private final TransactionRepo transactionRepo = mock(TransactionRepo.class);
	private final MockEnvironment environment = new MockEnvironment()
			.withProperty("local.server.port", "8080");

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final Logger logger = (Logger) LoggerFactory.getLogger(DevTestDataLogger.class);

	private final DevTestDataLogger devTestDataLogger =
			new DevTestDataLogger(accountRepo, transactionRepo, environment);

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
		final AccountEntity alice = account("Alice", "1000.0000", "4539148803436467");
		final AccountEntity bob = account("Bob", "250.5000", "6011000990139424");
		when(accountRepo.findAll()).thenReturn(List.of(alice, bob));

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
				// observability links — defaults match the compose.yaml LGTM container
				.contains("Grafana").contains("http://localhost:3000")
				.contains("Prometheus").contains("http://localhost:9090")
				.contains(alice.getId().toString())
				.contains("Alice").contains("1000.0000")
				.contains(bob.getId().toString())
				.contains("Bob").contains("250.5000")
				.contains(debit.getId().toString())
				.contains("DEBIT").contains("950.0000")
				// card numbers surface on each account line, plus a card-lookup curl example
				.contains("card=4539148803436467")
				.contains("Card lookup")
				// ATM frontend section — cards derived from live accounts, defaults from Environment
				.contains("ATM cards")
				.contains("Alice -> 4539148803436467")
				.contains("Bob -> 6011000990139424")
				.contains("ATM PIN").contains("1234")
				.contains("ATM frontend").contains("http://localhost:5173")
				.contains("ATM repo").contains("github.com/sean-huni/fe-bank-withdrawal")
				.contains("ATM launch").contains("npm install").contains("npm run dev");
	}

	@Test
	void bannerWarnsWhenNoAccountsAreSeeded() {
		when(accountRepo.findAll()).thenReturn(List.of());

		devTestDataLogger.logTestData();

		assertThat(loggedText()).contains("No accounts found");
	}

	private AccountEntity account(final String holder, final String balance, final String cardNumber) {
		final AccountEntity account = new AccountEntity(holder, new BigDecimal(balance), "EUR", cardNumber);
		account.assignIdIfMissing();
		return account;
	}

	private String loggedText() {
		return appender.list.stream()
				.map(ILoggingEvent::getFormattedMessage)
				.collect(Collectors.joining("\n"));
	}
}
