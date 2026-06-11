package com.example.bank.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
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
 *
 * <p>Dual authentication. A request authenticates EITHER as a kiosk customer via
 * the HttpSession established by card+PIN/passkey login (browser, cookie-borne),
 * OR as an OAuth2 caller via a JWT bearer token (Swagger try-it-out, m2m
 * scripting). Token scopes surface as {@code SCOPE_*} authorities — {@code
 * atm.read} / {@code atm.write} / {@code atm.ops}. Own-account access is narrowed
 * at the method layer by {@link AccountAccessEvaluator} (hence
 * {@link EnableMethodSecurity}): a bearer caller reaches an account either through
 * the {@code atm.read}/{@code atm.write} scope or by being its owner. The embedded
 * authorization server that mints those tokens (its {@code @Order(1)} chain over
 * the {@code /oauth2/**} endpoints) lives in {@link AuthorizationServerConfig};
 * this {@code @Order(2)} chain is the application/resource-server chain.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
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

	/**
	 * HttpSession-backed {@link SecurityContextRepository} — defined as a bean so the
	 * ATM bootstrap controller constructor-injects it (Spring architecture canon)
	 * rather than field-initialising its own instance.
	 */
	@Bean
	public SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	@Order(2)
	public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
		final CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
		http
				.webAuthn(webAuthn -> webAuthn
						.rpName(passkey.rpName())
						.rpId(passkey.rpId())
						.allowedOrigins(passkey.origins().toArray(String[]::new)))
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.csrfTokenRequestHandler(csrfHandler)
						.ignoringRequestMatchers("/api/**", "/webauthn/authenticate/options", "/login/webauthn"))
				.addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
				// JWT bearer (Swagger try-it-out, m2m) — scopes surface as SCOPE_* authorities.
				.oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
				// Form login backs the authorization-code flow (/oauth2/authorize → /login).
				.formLogin(Customizer.withDefaults())
				// HTML navigations get the login redirect; API callers get a plain 401.
				.exceptionHandling(ex -> ex
						.defaultAuthenticationEntryPointFor(
								new LoginUrlAuthenticationEntryPoint("/login"),
								AuthorizationServerConfig.htmlOnlyMatcher())
						.defaultAuthenticationEntryPointFor(
								new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
								AnyRequestMatcher.INSTANCE))
				.authorizeHttpRequests(auth -> auth
						// ── public: the login mechanisms themselves
						.requestMatchers(HttpMethod.GET, "/api/*/cards/*").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/*/cards/*/pin").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/*/atm/session", "/api/*/atm/session/end").permitAll()
						.requestMatchers("/webauthn/authenticate/options", "/login/webauthn").permitAll()
						.requestMatchers("/login").permitAll()
						// ── public: docs UI (its try-it-out calls are individually secured), probes, error dispatch
						.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
						.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
						.requestMatchers("/error").permitAll()
						// ── ops-scoped management surface
						.requestMatchers("/actuator/**").hasAuthority("SCOPE_atm.ops")
						// ── everything else: accounts API (method security narrows to scope-or-owner),
						//    webauthn registration ceremony, the whoami snapshot
						.anyRequest().authenticated())
				// Shared kiosk: create a session only when one is needed.
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
		return http.build();
	}
}
