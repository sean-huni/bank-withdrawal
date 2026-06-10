package com.example.bank.dev;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.example.bank.ctl.AccountTransactionController;
import com.example.bank.api.validation.AllowedSortProperties;
import com.example.bank.config.SupportedLanguages;
import com.example.bank.data.model.AccountEntity;
import com.example.bank.data.model.CardEntity;
import com.example.bank.data.model.TransactionEntity;
import com.example.bank.data.repo.AccountRepo;
import com.example.bank.data.repo.CardRepo;
import com.example.bank.data.repo.TransactionRepo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Prints everything needed to drive the API from Swagger UI to the console
 * once the dev-profile app is up: URLs, the whitelisted sort properties and
 * the live account/transaction ids (the Liquibase seed generates fresh UUIDs
 * per database, so they cannot be documented statically). The sort whitelist
 * is read reflectively from the statement endpoint's
 * {@link AllowedSortProperties} — a single source of truth that cannot drift.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestDataLogger {

	private static final int RECENT_TRANSACTIONS = 5;

	private final AccountRepo accountRepo;
	private final CardRepo cardRepo;
	private final TransactionRepo transactionRepo;
	private final Environment environment;

	@EventListener(ApplicationReadyEvent.class)
	public void logTestData() {
		final String baseUrl = "http://localhost:%s".formatted(
				environment.getProperty("local.server.port", "8080"));
		final StringBuilder banner = new StringBuilder();
		banner.append("\n==================== DEV TEST DATA — Swagger UI cheat sheet ====================\n");
		banner.append("Swagger UI      : %s/swagger-ui.html\n".formatted(baseUrl));
		banner.append("OpenAPI spec    : %s/v3/api-docs\n".formatted(baseUrl));
		banner.append("Base path       : /api/v1/accounts/{accountId}  (POST /withdrawals | POST /deposits | GET /transactions | GET /transactions/{transactionId})\n");
		banner.append("Card greeting   : curl %s/api/v1/cards/{cardNumber}  (holder + masked number; NO balance)\n"
				.formatted(baseUrl));
		banner.append("PIN verify      : curl -X POST %s/api/v1/cards/{cardNumber}/pin -H 'Content-Type: application/json' -d '{\"pin\":\"1234\"}'  (200 with balance; 401 PIN_INVALID)\n"
				.formatted(baseUrl));
		banner.append("Sortable fields : %s  (statement ?sort=)\n".formatted(String.join(", ", statementSortProperties())));
		banner.append("Sort examples   : sort=createdAt,desc | sort=amount,asc | sort=[\"amount,asc\"] (Swagger-style, also accepted)\n");
		banner.append("Locales         : %s  (Accept-Language header; unsupported values fall back to English)\n"
				.formatted(String.join(", ", SupportedLanguages.TAGS)));
		// last tag = the newest non-default locale — stays true when TAGS grows
		banner.append("Locale example  : curl -H 'Accept-Language: %s' %s/api/v1/accounts/{accountId}/transactions\n"
				.formatted(SupportedLanguages.TAGS.getLast(), baseUrl));
		// observability UIs from compose.yml (grafana/otel-lgtm); .env can override the URLs
		banner.append("Grafana         : %s  (LGTM all-in-one — dashboards + Explore for metrics/traces/logs)\n"
				.formatted(environment.getProperty("GRAFANA_URL", "http://localhost:3000")));
		banner.append("Prometheus      : %s  (raw metrics UI, embedded in the LGTM container)\n"
				.formatted(environment.getProperty("PROMETHEUS_URL", "http://localhost:9090")));
		banner.append("Idempotency-Key : %s  (fresh UUID — required header on every POST; reuse replays, new key per new operation)\n"
				.formatted(UUID.randomUUID()));
		appendAtmFrontend(banner);
		appendAccounts(banner);
		banner.append("=================================================================================");
		log.info("{}", banner);
	}

	/**
	 * Everything a tester needs to drive the ATM React app from one console block.
	 * The card list is derived from the live accounts (never hardcoded) so it stays
	 * true when the Liquibase seed changes; the URLs honor the {@code ${VAR:default}}
	 * convention via {@link Environment} so a clean clone still prints sane defaults.
	 */
	private void appendAtmFrontend(final StringBuilder banner) {
		final Map<UUID, String> holderById = new HashMap<>();
		accountRepo.findAll().forEach(account -> holderById.put(account.getId(), account.getHolderName()));
		final List<CardEntity> cardEntities = new ArrayList<>();
		cardRepo.findAll().forEach(cardEntities::add);
		final String cards = cardEntities.stream()
				.map(card -> "%s -> %s".formatted(
						holderById.getOrDefault(card.getAccountId(), "?"), card.getCardNumber()))
				.collect(Collectors.joining(" | "));
		banner.append("ATM cards     : %s  (demo cards — drive the ATM React app)\n".formatted(cards));
		banner.append("ATM PIN       : 1234  (demo; BCrypt-verified server-side against the seeded hash)\n");
		banner.append("ATM frontend  : %s\n".formatted(
				environment.getProperty("FRONTEND_URL", "http://localhost:5173")));
		banner.append("ATM repo      : %s\n".formatted(
				environment.getProperty("FRONTEND_REPO_URL", "https://github.com/sean-huni/fe-bank-withdrawal")));
		banner.append("ATM launch    : cd fe-bank-withdrawal && cp .env.example .env && npm install && npm run dev  (or: npm run setup)\n");
	}

	private void appendAccounts(final StringBuilder banner) {
		final List<AccountEntity> accounts = new ArrayList<>();
		accountRepo.findAll().forEach(accounts::add);
		if (accounts.isEmpty()) {
			banner.append("Accounts        : No accounts found — did the Liquibase seed (002-seed-accounts) run against this database?\n");
			return;
		}
		banner.append("Accounts (id | holder | balance | version):\n");
		for (final AccountEntity account : accounts) {
			banner.append("  %s | %s | %s %s | v%s\n".formatted(account.getId(), account.getHolderName(),
					account.getBalance(), account.getCurrency(), account.getVersion()));
			appendTransactions(banner, account.getId());
		}
	}

	private void appendTransactions(final StringBuilder banner, final UUID accountId) {
		final Pageable latestFirst = PageRequest.of(0, RECENT_TRANSACTIONS,
				Sort.by(Sort.Direction.DESC, "createdAt"));
		final List<TransactionEntity> transactions =
				transactionRepo.findByAccountId(accountId, latestFirst).getContent();
		if (transactions.isEmpty()) {
			banner.append("      no transactions yet — POST a withdrawal or deposit to create one\n");
			return;
		}
		for (final TransactionEntity transaction : transactions) {
			banner.append("      tx %s | %s | amount=%s | balanceAfter=%s | createdAt=%s\n".formatted(
					transaction.getId(), transaction.getType(), transaction.getAmount(),
					transaction.getBalanceAfter(), transaction.getCreatedAt()));
		}
	}

	/**
	 * The whitelist lives on the statement endpoint's {@code Pageable}
	 * parameter; failing loudly on a signature change beats silently printing
	 * a stale list.
	 */
	private List<String> statementSortProperties() {
		try {
			final Parameter[] parameters = AccountTransactionController.class
					.getMethod("statement", UUID.class, Pageable.class).getParameters();
			return Arrays.stream(parameters)
					.map(parameter -> parameter.getAnnotation(AllowedSortProperties.class))
					.filter(Objects::nonNull)
					.flatMap(allowed -> Arrays.stream(allowed.value()))
					.collect(Collectors.toList());
		} catch (final NoSuchMethodException ex) {
			throw new IllegalStateException(
					"statement endpoint signature changed — update DevTestDataLogger", ex);
		}
	}
}
