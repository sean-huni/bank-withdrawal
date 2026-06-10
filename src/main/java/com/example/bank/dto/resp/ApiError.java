package com.example.bank.dto.resp;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Machine-readable error: stable {@code code} for clients to branch on,
 * human-readable {@code message}, optional per-field violations.
 */
public record ApiError(
		String code,
		String message,
		List<FieldViolation> violations) {

	/**
	 * {@code code} is the bundle key (machine-readable, locale-independent);
	 * {@code message} the localized text; {@code rejectedValue} the offending
	 * input, stringified and truncated. Nulls are omitted from the payload.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FieldViolation(String field, String code, String message, String rejectedValue) {
	}

	public static ApiError of(final String code, final String message) {
		return new ApiError(code, message, List.of());
	}
}
