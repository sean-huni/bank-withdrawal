package com.example.bank.dto.req;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Command payload for {@code POST .../withdrawals}. */
@Schema(description = "Withdrawal command — the account is identified by the request path")
public record WithdrawalRequest(
		@Schema(description = "Amount to debit; positive, max 15 integer / 4 fraction digits", example = "250.00")
		@NotNull(message = "{error.amount.required}")
		@Positive(message = "{error.amount.positive}")
		@Digits(integer = 15, fraction = 4, message = "{error.amount.digits}")
		BigDecimal amount) {
}
