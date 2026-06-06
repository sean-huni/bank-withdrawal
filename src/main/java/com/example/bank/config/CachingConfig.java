package com.example.bank.config;

import java.time.Duration;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Caffeine-backed cache for the immutable ledger. Entries are write-once, so
 * a cached transaction can never go stale — the TTL/size bounds exist purely
 * to cap memory. {@code recordStats()} feeds hit/miss rates to Actuator.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CachingConfig {

	public static final String TRANSACTION_BY_ID = "transaction-by-id";

	@Bean
	public CacheManager cacheManager() {
		final CaffeineCacheManager cacheManager = new CaffeineCacheManager(TRANSACTION_BY_ID);
		cacheManager.setCaffeine(Caffeine.newBuilder()
				.maximumSize(10_000)
				.expireAfterWrite(Duration.ofMinutes(10))
				.recordStats());
		return cacheManager;
	}
}
