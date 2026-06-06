package com.example.bank.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Programmatic transaction support for the idempotency aspect — it must open
 * the transaction itself so the key reservation and the business mutation
 * commit (or roll back) as one unit.
 */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

	@Bean
	public TransactionTemplate transactionTemplate(final PlatformTransactionManager transactionManager) {
		return new TransactionTemplate(transactionManager);
	}
}
