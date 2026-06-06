package com.example.bank;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import ch.qos.logback.classic.Logger;
import io.opentelemetry.api.OpenTelemetry;

@SpringBootTest
@Import(TestConfig.class)
class BankWithdrawalAppTests {

	@Autowired
	private OpenTelemetry openTelemetry;

	@Test
	void contextLoads() {
	}

	@Test
	void openTelemetrySdkIsConfigured() {
		assertThat(openTelemetry).isNotNull();
	}

	@Test
	void openTelemetryLogbackAppenderIsAttached() {
		final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		assertThat(rootLogger.getAppender("OTEL"))
				.as("OTEL appender from logback-spring.xml attached to the root logger")
				.isNotNull();
	}
}
