package com.example.bank.api.dto.resp;

import java.util.List;

/**
 * Machine-readable error: stable {@code code} for clients to branch on,
 * human-readable {@code message}, optional per-field violations.
 */
public record ApiError(
		String code,
		String message,
		List<FieldViolation> violations) {

	public record FieldViolation(String field, String message) {
	}

	public static ApiError of(final String code, final String message) {
		return new ApiError(code, message, List.of());
	}
}
