package com.example.bank.config.properties;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * WebAuthn relying-party settings for the passkey-enabled ATM. The relying
 * party is THIS bank: {@code rpId} is the registrable domain the credential is
 * scoped to and {@code origins} are the exact origins the browser ceremony is
 * allowed to run from (registration binds the credential to its origin).
 *
 * <p>Defaults (application.yml) target local development ({@code localhost} +
 * the Vite dev server). Every value is overridable from the environment per the
 * {@code .env} convention (precedence: yml default &lt; {@code .env} &lt; real env).
 * In production {@code rpId} is the bank's apex domain and origins are HTTPS —
 * see the README "Passkey-enabled ATM" notes.
 *
 * <p>Validated at startup ({@code @Validated}) so a blank/missing mandatory value
 * fails fast rather than yielding an opaque ceremony failure on first tap.
 * Messages are braced i18n keys so the failure is localized and machine-readable
 * (point 18) — mirrors {@code AwsProperties}.
 */
@Validated
@ConfigurationProperties(prefix = "atm.passkey")
public record PasskeyProperties(
		@NotBlank(message = "{config.passkey.rp-id.required}") String rpId,
		@NotBlank(message = "{config.passkey.rp-name.required}") String rpName,
		@NotEmpty(message = "{config.passkey.origins.required}") List<String> origins) {
}
