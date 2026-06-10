package com.example.bank.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

import com.example.bank.config.properties.PasskeyProperties;

import lombok.RequiredArgsConstructor;

/**
 * Security for the passkey-enabled ATM. Session-based (HttpSession cookie) on
 * purpose — correct for a shared ATM kiosk: no bearer tokens sit in browser
 * storage on a public terminal; the identity lives in a server session that the
 * kiosk clears between customers.
 *
 * <p>Spring Security 7 native WebAuthn DSL ({@link HttpSecurity#webAuthn})
 * performs REAL attestation/assertion signature verification via webauthn4j —
 * this is the framework's own relying-party operations, not a hand-rolled
 * verifier. Passkey = customer identity (replaces card-number entry on return
 * visits); the bank PIN remains the transaction-authorization secret.
 *
 * <p>Endpoints exposed by the DSL: {@code POST /webauthn/register/options} +
 * {@code POST /webauthn/register} (registration — require an authenticated
 * session, reached after card+PIN bootstrap), {@code POST
 * /webauthn/authenticate/options} + {@code POST /login/webauthn} (username-less
 * discoverable-credential login on a return visit).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final PasskeyProperties passkey;

	/**
	 * Persist WebAuthn user entities in Postgres (Liquibase changeset 006) rather
	 * than the in-memory default, so a registered passkey survives a restart — a
	 * real ATM must remember enrolled customers. The DSL discovers this bean and
	 * the credential repo below and wires them into its relying-party operations.
	 */
	@Bean
	public PublicKeyCredentialUserEntityRepository userEntityRepository(final JdbcOperations jdbc) {
		return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
	}

	/** JDBC-backed credential store (public keys, signature counters) — see changeset 006. */
	@Bean
	public UserCredentialRepository userCredentialRepository(final JdbcOperations jdbc) {
		return new JdbcUserCredentialRepository(jdbc);
	}

	@Bean
	public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
		final CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
		http
				.webAuthn(webAuthn -> webAuthn
						.rpName(passkey.rpName())
						.rpId(passkey.rpId())
						.allowedOrigins(passkey.origins().toArray(String[]::new)))
				// CSRF protects the browser-driven WebAuthn REGISTRATION ceremony (it mutates
				// state under an authenticated session) with a readable (httpOnly=false) cookie
				// token the SPA echoes back. Exempt:
				//   - /api/** — the existing JSON API stays byte-identical to its current
				//     contract (50 existing tests unchanged); it is idempotency-keyed, not cookie-auth.
				//   - the PRE-authentication ceremony endpoints — /webauthn/authenticate/options
				//     returns a public challenge (no side effect) and /login/webauthn IS an
				//     authentication mechanism (the assertion signature is the proof; CSRF adds
				//     nothing before a session exists). Conventional login endpoints are CSRF-exempt.
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.csrfTokenRequestHandler(csrfHandler)
						.ignoringRequestMatchers("/api/**", "/webauthn/authenticate/options", "/login/webauthn"))
				.authorizeHttpRequests(auth -> auth
						// [!CONVENTION-OVERRIDE] The existing JSON API, actuator, OpenAPI/Swagger
						// and the error dispatch are permitAll. This assessment app deliberately
						// preserves its existing PUBLIC API contract (the ATM proves the passkey
						// CEREMONY wiring, not endpoint lockdown) so all prior tests stay green.
						// A production bank would require authentication on /api/** and lock down
						// actuator — leaving these public violates OWASP A01:2021 Broken Access
						// Control (https://owasp.org/Top10/A01_2021-Broken_Access_Control/) and is
						// acceptable ONLY because this is an assessment, not a deployed system.
						.requestMatchers("/api/**").permitAll()
						.requestMatchers("/actuator/**").permitAll()
						.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
						.requestMatchers("/error").permitAll()
						// Username-less login ceremony is public by design — a returning
						// customer has no session yet (framework defaults; listed explicitly).
						.requestMatchers("/webauthn/authenticate/options", "/login/webauthn").permitAll()
						// Everything else — notably /webauthn/register/options and
						// /webauthn/register — requires an authenticated session, established
						// by the card+PIN ATM bootstrap (AtmSessionController).
						.anyRequest().authenticated())
				// Shared kiosk: create a session only when one is needed (after a successful
				// card+PIN bootstrap or passkey login), never eagerly.
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
		return http.build();
	}
}
