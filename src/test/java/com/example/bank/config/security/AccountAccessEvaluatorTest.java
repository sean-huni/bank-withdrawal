package com.example.bank.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

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
	}
}
