package com.example.bank.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Card + PIN submitted to bootstrap an authenticated ATM session — the
 * "insert card, enter PIN" step that establishes customer identity on the
 * kiosk so the passkey-enrolment ceremony becomes reachable. Raw 16-digit card
 * and 4-digit PIN over HTTPS; the PIN is BCrypt-verified and never logged.
 */
public record AtmSessionRequest(
		@Schema(description = "16-digit card number", example = "4539148803436467")
		@NotBlank @Pattern(regexp = "\\d{16}", message = "{error.card.invalid}") String cardNumber,
		@Schema(description = "4-digit PIN", example = "1234")
		@NotBlank @Pattern(regexp = "\\d{4}", message = "{error.pin.invalid-format}") String pin) {
}
