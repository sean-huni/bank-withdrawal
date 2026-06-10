package com.example.bank.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** PIN submitted for verification. Raw 4-digit PIN over HTTPS; never logged. */
public record PinVerifyRequest(
		@Schema(description = "4-digit PIN", example = "1234")
		@NotBlank @Pattern(regexp = "\\d{4}", message = "{error.pin.invalid-format}") String pin) {
}
