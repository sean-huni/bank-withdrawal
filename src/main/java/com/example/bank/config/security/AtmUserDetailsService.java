package com.example.bank.config.security;

import java.util.UUID;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.bank.config.properties.SecurityProperties;
import com.example.bank.data.repo.AccountRepo;

/**
 * Single identity source for BOTH authentication mechanisms:
 *
 * <ul>
 *   <li>the demo <b>operator</b> — signs in on the authorization-server form login
 *       (Swagger "Authorize" → auth-code + PKCE);</li>
 *   <li><b>ATM customers</b> keyed by accountId UUID — Spring Security 7's
 *       {@code WebAuthnAuthenticationProvider} resolves the verified passkey's
 *       user-entity name through this service. Without this bean, Boot's default
 *       in-memory "user" satisfies the bean lookup but can never resolve an
 *       accountId, so every passkey login 401s after a VALID assertion.</li>
 * </ul>
 *
 * <p>Customers carry a per-start random sentinel password: they authenticate via
 * PIN or passkey, never via the form, and a random {@code {noop}} value can't be
 * guessed or matched.
 */
@Service
public class AtmUserDetailsService implements UserDetailsService {

	private static final String PASSWORD_LOGIN_DISABLED = "{noop}" + UUID.randomUUID();

	private final SecurityProperties security;
	private final AccountRepo accountRepo;
	private final String encodedOperatorPassword;

	public AtmUserDetailsService(
			final SecurityProperties security,
			final AccountRepo accountRepo,
			final PasswordEncoder passwordEncoder) {
		this.security = security;
		this.accountRepo = accountRepo;
		this.encodedOperatorPassword = passwordEncoder.encode(security.operatorPassword());
	}

	@Override
	public UserDetails loadUserByUsername(final String username) throws UsernameNotFoundException {
		if (username == null) {
			throw new UsernameNotFoundException("Unknown principal");
		}
		if (security.operatorUsername().equals(username)) {
			return User.withUsername(username)
					.password(encodedOperatorPassword)
					.roles("OPERATOR")
					.build();
		}
		return accountRepo.findById(parseAccountId(username))
				.map(account -> User.withUsername(account.getId().toString())
						.password(PASSWORD_LOGIN_DISABLED)
						.roles("CUSTOMER")
						.build())
				.orElseThrow(() -> new UsernameNotFoundException("Unknown principal"));
	}

	private UUID parseAccountId(final String username) {
		try {
			return UUID.fromString(username);
		} catch (final IllegalArgumentException ex) {
			throw new UsernameNotFoundException("Unknown principal");
		}
	}
}
