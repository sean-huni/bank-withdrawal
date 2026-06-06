package com.example.bank.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;

/**
 * Enables {@code @Observed} on beans — Micrometer's AOP aspect records
 * operation timings (metrics) and trace spans from a single annotation,
 * instead of a hand-rolled stopwatch aspect.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

	@Bean
	public ObservedAspect observedAspect(final ObservationRegistry observationRegistry) {
		return new ObservedAspect(observationRegistry);
	}
}
