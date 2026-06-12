package com.example.bank.config.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import com.example.bank.config.properties.SecurityProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import lombok.RequiredArgsConstructor;

/**
 * Embedded OAuth 2.1 authorization server (Spring Authorization Server, merged
 * into Spring Security 7). Issues the JWTs the API's resource-server side
 * validates — same JVM, no HTTP self-call ({@link #jwtDecoder} reads the
 * {@link JWKSource} bean directly, which also works under RANDOM_PORT tests).
 *
 * <p>Clients (demo values, env-overridable via {@code atm.security.*}):
 * <ul>
 *   <li><b>swagger-ui</b> — public client, authorization-code + PKCE (OAuth 2.1
 *       mandates PKCE for public clients). Consent screen disabled: SAS ships no
 *       default consent page and a demo app doesn't need one.</li>
 *   <li><b>atm-ops</b> — confidential client, client-credentials, for
 *       m2m/scripting and the Cucumber suite.</li>
 * </ul>
 *
 * <p>The signing key is generated per start (in-memory JWK): issued tokens die
 * with the process — acceptable for the assessment app, NOT for production.
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class AuthorizationServerConfig {

	private final SecurityProperties security;
	private final PasswordEncoder passwordEncoder;

	@Bean
	@Order(1)
	public SecurityFilterChain authorizationServerSecurityFilterChain(final HttpSecurity http) throws Exception {
		final OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
		http
				.securityMatcher(authorizationServer.getEndpointsMatcher())
				.with(authorizationServer, server -> { })
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				// Browser at /oauth2/authorize without a session → form login; non-HTML → 401.
				.exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
						new LoginUrlAuthenticationEntryPoint("/login"),
						htmlOnlyMatcher()));
		return http.build();
	}

	/** Matches real browser navigations only — ignores Accept: *&#47;* API clients. */
	static MediaTypeRequestMatcher htmlOnlyMatcher() {
		final MediaTypeRequestMatcher html = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
		html.setIgnoredMediaTypes(Set.of(MediaType.ALL));
		return html;
	}

	@Bean
	public RegisteredClientRepository registeredClientRepository() {
		final RegisteredClient swaggerUi = RegisteredClient.withId(UUID.randomUUID().toString())
				.clientId("swagger-ui")
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://localhost:8080/swagger-ui/oauth2-redirect.html")
				.scope("atm.read")
				.scope("atm.write")
				.scope("atm.ops")
				.clientSettings(ClientSettings.builder()
						.requireProofKey(true)
						.requireAuthorizationConsent(false)
						.build())
				.build();
		final RegisteredClient atmOps = RegisteredClient.withId(UUID.randomUUID().toString())
				// The app exposes a BCryptPasswordEncoder bean; Spring Security 7 SAS picks it up
				// and uses it to verify client secrets, so the stored secret must be BCrypt-encoded
				// (not {noop}) to survive the passwordEncoder.matches() call in SAS's
				// ClientSecretAuthenticationProvider.  The raw secret stays in application.yml
				// (env-overridable) and is never stored anywhere in hashed form.
				.clientId("atm-ops")
				.clientSecret(passwordEncoder.encode(security.opsClientSecret()))
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
				.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
				.scope("atm.read")
				.scope("atm.write")
				.scope("atm.ops")
				.build();
		return new InMemoryRegisteredClientRepository(swaggerUi, atmOps);
	}

	@Bean
	public JWKSource<SecurityContext> jwkSource() {
		final KeyPair keyPair = generateRsaKey();
		final RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
				.privateKey((RSAPrivateKey) keyPair.getPrivate())
				.keyID(UUID.randomUUID().toString())
				.build();
		return new ImmutableJWKSet<>(new JWKSet(rsaKey));
	}

	private static KeyPair generateRsaKey() {
		try {
			final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		} catch (final NoSuchAlgorithmException ex) {
			throw new IllegalStateException("RSA key generation failed", ex);
		}
	}

	@Bean
	public JwtDecoder jwtDecoder(final JWKSource<SecurityContext> jwkSource) {
		return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
	}

	@Bean
	public AuthorizationServerSettings authorizationServerSettings() {
		return AuthorizationServerSettings.builder().build();
	}
}
