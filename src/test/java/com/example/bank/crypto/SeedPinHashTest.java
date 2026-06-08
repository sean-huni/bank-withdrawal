package com.example.bank.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Guards the BCrypt("1234") literal embedded in migration 005. If the seeded hash ever
 * stops verifying the demo PIN, this fails loudly instead of a mysterious 401 at runtime.
 */
class SeedPinHashTest {

	/** MUST equal the literal used in db/changelog/changes/005-normalize-cards.sql. */
	static final String SEED_PIN_HASH = "$2a$10$3b0uTtoVg.p3DDmDss8Xu.uRfWWNLCt5TGOrNDtWstPLnoVAIYlDq";

	@Test
	void seedHashVerifiesTheDemoPin() {
		assertThat(new BCryptPasswordEncoder().matches("1234", SEED_PIN_HASH)).isTrue();
		assertThat(new BCryptPasswordEncoder().matches("0000", SEED_PIN_HASH)).isFalse();
	}
}
