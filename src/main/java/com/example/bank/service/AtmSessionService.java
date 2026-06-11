package com.example.bank.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Service;

import com.example.bank.data.model.CardEntity;
import com.example.bank.data.model.AccountEntity;
import com.example.bank.data.repo.AccountRepo;
import com.example.bank.data.repo.CardRepo;
import com.example.bank.dto.resp.AccountResponse;
import com.example.bank.dto.resp.AtmSessionResponse;
import com.example.bank.dto.resp.AtmSessionSnapshotResponse;
import com.example.bank.exception.AccountNotFoundException;
import com.example.bank.mapper.AccountMapper;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;

/**
 * Bootstraps an authenticated ATM session from card + PIN — the creative
 * enrolment path. PIN verification reuses the existing {@link CardService} flow
 * (BCrypt match, never logged); on success this links the customer to a WebAuthn
 * {@link PublicKeyCredentialUserEntity} so the framework's registration ceremony
 * can attach a passkey to a STABLE identity.
 *
 * <p>Principal identity = the account UUID (never the card number — that is
 * neither logged nor used as a principal). The WebAuthn user-entity {@code name}
 * is that same UUID string (the framework keys {@code findByUsername} on the
 * authenticated principal name), and its {@code displayName} is the masked card
 * for a human-friendly authenticator prompt. Pre-saving the entity here (rather
 * than letting the framework auto-create one named after the principal) lets us
 * control the displayName and decide enrolment state.
 *
 * <p>This service stays free of servlet artifacts: it returns the
 * {@link Authentication} + response DTO and lets the controller persist the
 * security context into the HttpSession (the servlet concern lives at the
 * boundary).
 */
@Service
@RequiredArgsConstructor
public class AtmSessionService {

	/** Single role for an authenticated ATM customer. */
	static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";

	private final CardService cardService;
	private final PublicKeyCredentialUserEntityRepository userEntities;
	private final UserCredentialRepository userCredentials;
	private final AccountRepo accountRepo;
	private final CardRepo cardRepo;
	private final AccountMapper accountMapper;

	/** Outcome of a bootstrap: the session identity to persist + the body to return. */
	public record Bootstrap(Authentication authentication, AtmSessionResponse response) {
	}

	/**
	 * Verify card + PIN, establish the WebAuthn identity, and produce the session
	 * authentication. Throws the existing {@code CardNotFoundException} (404) /
	 * {@code PinInvalidException} (401) on the negative paths — identical wire shape
	 * to the existing card endpoints.
	 */
	@Observed(name = "atm.session.bootstrap")
	public Bootstrap bootstrap(final String cardNumber, final String pin) {
		final AccountResponse account = cardService.verifyPin(cardNumber, pin);
		final String principal = account.accountId().toString();

		final PublicKeyCredentialUserEntity userEntity =
				ensureUserEntity(principal, account.maskedCardNumber());
		final boolean passkeyEnrolled = !userCredentials.findByUserId(userEntity.getId()).isEmpty();

		final Authentication authentication = new UsernamePasswordAuthenticationToken(
				principal, null, AuthorityUtils.createAuthorityList(ROLE_CUSTOMER));
		return new Bootstrap(authentication,
				new AtmSessionResponse(account.accountId(), account.maskedCardNumber(), passkeyEnrolled));
	}

	/**
	 * Whoami for an authenticated session (card+PIN or passkey). The principal name
	 * is the accountId UUID for customers; non-customer principals (e.g. a JWT
	 * client) have no ATM session → 404 ACCOUNT_NOT_FOUND.
	 */
	@Observed(name = "atm.session.snapshot")
	public AtmSessionSnapshotResponse snapshot(final String principalName) {
		final UUID accountId = parseAccountId(principalName);
		final AccountEntity account = accountRepo.findById(accountId)
				.orElseThrow(() -> new AccountNotFoundException(accountId));
		final CardEntity card = cardRepo.findByAccountId(accountId)
				.orElseThrow(() -> new AccountNotFoundException(accountId));
		final PublicKeyCredentialUserEntity userEntity = userEntities.findByUsername(principalName);
		final boolean passkeyEnrolled =
				userEntity != null && !userCredentials.findByUserId(userEntity.getId()).isEmpty();
		return accountMapper.toSnapshot(account, card.getCardNumber(), passkeyEnrolled);
	}

	private UUID parseAccountId(final String principalName) {
		try {
			return UUID.fromString(principalName);
		} catch (final IllegalArgumentException ex) {
			// Non-UUID principal (e.g. a JWT client id like "atm-ops") → no ATM account → 404.
			// Use a zero UUID so AccountNotFoundException(null) cannot trip a null-check in the
			// error renderer — the zero UUID produces an unambiguous ACCOUNT_NOT_FOUND 404.
			return new UUID(0, 0);
		}
	}

	/**
	 * Idempotent: return the existing user entity for this principal, or create one
	 * whose id is derived deterministically from the principal so the same customer
	 * always maps to the same WebAuthn user id across sessions.
	 */
	private PublicKeyCredentialUserEntity ensureUserEntity(final String principal, final String maskedCard) {
		final PublicKeyCredentialUserEntity existing = userEntities.findByUsername(principal);
		if (existing != null) {
			return existing;
		}
		final PublicKeyCredentialUserEntity created = ImmutablePublicKeyCredentialUserEntity.builder()
				.id(new Bytes(principal.getBytes(StandardCharsets.UTF_8)))
				.name(principal)
				.displayName(maskedCard)
				.build();
		userEntities.save(created);
		return created;
	}
}
