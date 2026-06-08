package com.example.bank.api.dto.resp;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Account snapshot returned by the card-lookup endpoint — balance inquiry without exposing the PAN. */
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
