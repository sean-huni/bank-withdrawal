package com.example.bank.dto.resp;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Authenticated account snapshot returned by the PIN-verify endpoint — balance revealed only after a verified PIN, PAN masked. */
@Schema(description = "Account snapshot for a card: holder, masked card number and current balance")
public record AccountResponse(
		@Schema(description = "Owning account id; used for subsequent transaction calls")
		UUID accountId,
		@Schema(description = "Account holder display name", example = "Alice")
		String holderName,
		@Schema(description = "Card number masked to the last four digits", example = "•••• •••• •••• 6467")
		String maskedCardNumber,
		@Schema(description = "Current account balance", example = "1000.00")
		BigDecimal balance,
		@Schema(description = "ISO-4217 currency code", example = "EUR")
		String currency) {
}
