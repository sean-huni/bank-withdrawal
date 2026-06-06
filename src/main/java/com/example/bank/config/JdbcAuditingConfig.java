package com.example.bank.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;

import com.example.bank.jdbc.model.BaseEntity;

/**
 * Enables audit-timestamp population and assigns UUID ids before INSERT —
 * Spring Data JDBC always writes the id column, so the DB-side
 * {@code gen_random_uuid()} default never applies to new aggregates.
 */
@Configuration(proxyBeanMethods = false)
@EnableJdbcAuditing
public class JdbcAuditingConfig {

	@Bean
	public BeforeConvertCallback<BaseEntity> uuidAssigningCallback() {
		return entity -> {
			entity.assignIdIfMissing();
			return entity;
		};
	}
}
