package com.example.bank.config.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Object-level authorization (OWASP A01): a kiosk-session customer may only act
 * on their OWN account. Both auth paths name the principal by accountId UUID —
 * card+PIN ({@code UsernamePasswordAuthenticationToken}) and passkey
 * ({@code WebAuthnAuthentication.getName()} = user-entity name) — so one
 * comparison covers both. Referenced from {@code @PreAuthorize} as
 * {@code @accountAccess.isOwner(authentication, #accountId)}.
 */
@Component("accountAccess")
public class AccountAccessEvaluator {

	public boolean isOwner(final Authentication authentication, final UUID accountId) {
		return authentication != null && accountId != null
				&& accountId.toString().equals(authentication.getName());
	}
}
