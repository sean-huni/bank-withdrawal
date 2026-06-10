package com.example.bank.dto.req;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Command payload for {@code POST .../deposits}. */
@Schema(description = "Deposit command — the account is identified by the request path")
public record DepositRequest(
		@Schema(description = "Amount to credit; positive, max 15 integer / 4 fraction digits", example = "50.00")
		@NotNull(message = "{error.amount.required}")
		@Positive(message = "{error.amount.positive}")
		@Digits(integer = 15, fraction = 4, message = "{error.amount.digits}")
		BigDecimal amount) {
}
