package com.example.bank.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.example.bank.dto.resp.AccountResponse;
import com.example.bank.dto.resp.AtmSessionSnapshotResponse;
import com.example.bank.data.model.AccountEntity;

class AccountMapperTest {

	private final AccountMapper mapper = Mappers.getMapper(AccountMapper.class);

	@Test
	void mapsAccountPlusCardNumberAndMasks() {
		final AccountEntity entity = new AccountEntity("Alice", new BigDecimal("1000.00"), "EUR");

		final AccountResponse r = mapper.toAccountResponse(entity, "4539148803436467");

		assertThat(r.holderName()).isEqualTo("Alice");
		assertThat(r.balance()).isEqualByComparingTo("1000.00");
		assertThat(r.currency()).isEqualTo("EUR");
		assertThat(r.maskedCardNumber()).isEqualTo("•••• •••• •••• 6467");
	}

	@Test
	void toSnapshotMapsAllSixFieldsIncludingMaskedCardAndPasskeyEnrolled() {
		final AccountEntity entity = new AccountEntity("Bob", new BigDecimal("2500.00"), "USD");

		final AtmSessionSnapshotResponse r = mapper.toSnapshot(entity, "4539148803437777", true);

		assertThat(r.accountId()).isEqualTo(entity.getId());
		assertThat(r.holderName()).isEqualTo("Bob");
		assertThat(r.balance()).isEqualByComparingTo("2500.00");
		assertThat(r.currency()).isEqualTo("USD");
		assertThat(r.maskedCardNumber()).isEqualTo("•••• •••• •••• 7777");
		assertThat(r.passkeyEnrolled()).isTrue();
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
