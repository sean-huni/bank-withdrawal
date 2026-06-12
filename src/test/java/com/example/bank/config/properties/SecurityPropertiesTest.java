package com.example.bank.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The record backs real credentials; its toString must never echo them (OWASP logging). */
class SecurityPropertiesTest {

	@Test
	void toStringMasksTheSecretComponents() {
		final SecurityProperties properties =
				new SecurityProperties("operator", "real-password", "real-client-secret");

		assertThat(properties.toString())
				.contains("operator")
				.doesNotContain("real-password")
				.doesNotContain("real-client-secret");
	}
}
