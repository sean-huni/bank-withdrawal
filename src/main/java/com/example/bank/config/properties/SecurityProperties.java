package com.example.bank.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Demo identities for the OAuth 2.1 layer. The operator signs in on the
 * authorization-server form login (Swagger "Authorize"); the ops client secret
 * backs the client-credentials grant. Committed defaults follow the demo-PIN
 * convention (clean-clone rule); every value is env-overridable per the
 * 12FactorApp Alignment — https://12factor.net/ — .env convention.
 */
@Validated
@ConfigurationProperties(prefix = "atm.security")
public record SecurityProperties(
		@NotBlank(message = "{config.security.operator-username.required}") String operatorUsername,
		@NotBlank(message = "{config.security.operator-password.required}") String operatorPassword,
		@NotBlank(message = "{config.security.ops-client-secret.required}") String opsClientSecret) {
}
