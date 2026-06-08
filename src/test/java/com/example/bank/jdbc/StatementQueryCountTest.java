package com.example.bank.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import com.example.bank.TestcontainersConfiguration;
import com.example.bank.domain.TransactionType;
import com.example.bank.jdbc.model.AccountEntity;
import com.example.bank.jdbc.model.TransactionEntity;
import com.example.bank.jdbc.repo.AccountRepo;
import com.example.bank.jdbc.repo.TransactionRepo;
import com.example.bank.service.AccountTransactionService;

import net.ttddyy.dsproxy.QueryCountHolder;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;

/**
 * Regression guard against per-row query fan-out (the N+1 problem): the
 * statement page must issue a CONSTANT number of SELECTs regardless of how
 * many transactions the page contains. Counted at the DataSource, so any
 * fan-out introduced anywhere below the service shows up here.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, StatementQueryCountTest.QueryCountConfig.class})
class StatementQueryCountTest {

	@Autowired
	private AccountTransactionService transactionService;

	@Autowired
	private AccountRepo accountRepo;

	@Autowired
	private TransactionRepo transactionRepo;

	@Test
	void statementSelectCountDoesNotGrowWithPageContent() {
		final UUID smallAccount = seedAccountWithTransactions(2);
		final UUID largeAccount = seedAccountWithTransactions(8);

		final long selectsForSmallPage = selectsFor(smallAccount);
		final long selectsForLargePage = selectsFor(largeAccount);

		assertThat(selectsForSmallPage)
				.as("proxy must be active — SELECT count must be > 0 (wrapper not engaged if 0)")
				.isGreaterThan(0);

		assertThat(selectsForLargePage)
				.as("SELECT count must be constant in page content (no per-row fan-out)")
				.isEqualTo(selectsForSmallPage);
	}

	private long selectsFor(final UUID accountId) {
		QueryCountHolder.clear();
		transactionService.statement(accountId, PageRequest.of(0, 10));
		return QueryCountHolder.getGrandTotal().getSelect();
	}

	private UUID seedAccountWithTransactions(final int count) {
		final AccountEntity account = accountRepo.save(
				new AccountEntity("n1-probe-%d".formatted(count), new BigDecimal("1000.00"), "EUR", "%016d".formatted(count)));
		for (int i = 0; i < count; i++) {
			transactionRepo.save(TransactionEntity.create(account.getId(), TransactionType.CREDIT,
					new BigDecimal("10.00"), new BigDecimal("1000.00"), UUID.randomUUID()));
		}
		return account.getId();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class QueryCountConfig {

		/** static: must run before regular bean initialization to wrap the DataSource. */
		@Bean
		static BeanPostProcessor queryCountingDataSourceWrapper() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(final Object bean, final String beanName) {
					return bean instanceof DataSource dataSource && !(bean instanceof ProxyDataSource)
							? ProxyDataSourceBuilder.create(dataSource).countQuery().build()
							: bean;
				}
			};
		}
	}
}
