package com.example.bank.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;

class AccountAccessEvaluatorTest {

	private final AccountAccessEvaluator evaluator = new AccountAccessEvaluator();
	private final UUID accountId = UUID.randomUUID();

	@Test
	void ownerIsAllowed() {
		final var auth = new TestingAuthenticationToken(accountId.toString(), null, "ROLE_CUSTOMER");
		assertThat(evaluator.isOwner(auth, accountId)).isTrue();
	}

	@Test
	void differentAccountIsDenied() {
		final var auth = new TestingAuthenticationToken(UUID.randomUUID().toString(), null, "ROLE_CUSTOMER");
		assertThat(evaluator.isOwner(auth, accountId)).isFalse();
	}

	@Test
	void nullsAreDenied() {
		assertThat(evaluator.isOwner(null, accountId)).isFalse();
		assertThat(evaluator.isOwner(new TestingAuthenticationToken("x", null), null)).isFalse();
		assertThat(evaluator.isOwner(null, null)).isFalse();
	}

	@Test
	void passkeyAuthenticationPrincipalIsRecognisedAsOwner() {
		final var userEntity = ImmutablePublicKeyCredentialUserEntity.builder()
				.id(new Bytes(accountId.toString().getBytes(StandardCharsets.UTF_8)))
				.name(accountId.toString())
				.displayName("•••• •••• •••• 6467")
				.build();
		final var auth = new WebAuthnAuthentication(userEntity,
				AuthorityUtils.createAuthorityList("ROLE_CUSTOMER"));
		assertThat(evaluator.isOwner(auth, accountId)).isTrue();
	}
}
