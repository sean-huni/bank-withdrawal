package com.example.bank.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.example.bank.api.dto.resp.AccountResponse;
import com.example.bank.jdbc.model.AccountEntity;

class AccountMapperTest {

	private final AccountMapper mapper = Mappers.getMapper(AccountMapper.class);

	@Test
	void mapsEntityAndMasksAllButLastFour() {
		final AccountEntity entity = new AccountEntity("Alice", new BigDecimal("1000.00"), "EUR", "4539148803436467");

		final AccountResponse response = mapper.toAccountResponse(entity);

		assertThat(response.holderName()).isEqualTo("Alice");
		assertThat(response.balance()).isEqualByComparingTo("1000.00");
		assertThat(response.currency()).isEqualTo("EUR");
		assertThat(response.maskedCardNumber()).isEqualTo("•••• •••• •••• 6467");
	}

	@Test
	void maskCardReturnsFullyMaskedFallbackForNullWithoutThrowing() {
		assertThatCode(() -> AccountMapper.maskCard(null)).doesNotThrowAnyException();
		assertThat(AccountMapper.maskCard(null)).isEqualTo("•••• •••• •••• ••••");
	}

	@Test
	void maskCardReturnsFullyMaskedFallbackForShortInputWithoutThrowing() {
		assertThatCode(() -> AccountMapper.maskCard("12")).doesNotThrowAnyException();
		assertThat(AccountMapper.maskCard("12")).isEqualTo("•••• •••• •••• ••••");
	}
}
