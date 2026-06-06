package com.example.bank.api.dto.resp;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Unified response envelope for all REST endpoints. {@code traceId} ties a
 * client-visible error to the server-side trace.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
		boolean success,
		T data,
		ApiError error,
		Instant timestamp,
		String traceId) {

	public static <T> ApiResponse<T> ok(final T data, final String traceId) {
		return new ApiResponse<>(true, data, null, Instant.now(), traceId);
	}

	public static <T> ApiResponse<T> failure(final ApiError error, final String traceId) {
		return new ApiResponse<>(false, null, error, Instant.now(), traceId);
	}
}
