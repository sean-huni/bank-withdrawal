package com.example.bank.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;

/** Greeting-only card snapshot for the unauthenticated lookup — NO balance, NO accountId. */
@Schema(description = "Card greeting: holder name and masked card number (no balance until PIN verified)")
public record CardSummaryResponse(
		@Schema(description = "Account holder display name", example = "Alice") String holderName,
		@Schema(description = "Card number masked to last four", example = "•••• •••• •••• 6467") String maskedCardNumber) {
}
