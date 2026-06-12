package com.example.bank.api.advice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.bank.dto.resp.ApiResponse;
import com.example.bank.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Last line of defence ONLY — the {@code Exception} catch-all that turns
 * anything the categorised handlers ({@link ValidationExceptionHandler},
 * {@link BusinessExceptionHandler}, {@link PersistenceExceptionHandler},
 * {@link ApiVersionExceptionHandler}) did not claim into a non-leaking 500.
 * Ordered {@code LOWEST_PRECEDENCE} so the specific categories always win.
 */
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

	private final ErrorResponseFactory errors;

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ApiResponse<Void> handleUnexpected(final Exception ex) {
		log.error("Unhandled exception", ex);
		return errors.failure(errors.errorOf(ErrorCode.INTERNAL_ERROR));
	}
}
