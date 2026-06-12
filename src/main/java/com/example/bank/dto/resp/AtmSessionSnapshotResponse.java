package com.example.bank.dto.resp;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Whoami snapshot of the authenticated ATM session — hydrates the kiosk UI after a passkey login (no card insertion). */
@Schema(description = "Authenticated session snapshot: account, masked card, balance and passkey enrolment")
public record AtmSessionSnapshotResponse(
		@Schema(description = "Owning account id; the authenticated session principal") UUID accountId,
		@Schema(description = "Account holder display name", example = "Alice") String holderName,
		@Schema(description = "Card number masked to the last four digits", example = "•••• •••• •••• 6467") String maskedCardNumber,
		@Schema(description = "Current account balance", example = "1000.00") BigDecimal balance,
		@Schema(description = "ISO-4217 currency code", example = "EUR") String currency,
		@Schema(description = "True if this customer has a passkey enrolled", example = "false") boolean passkeyEnrolled) {
}
