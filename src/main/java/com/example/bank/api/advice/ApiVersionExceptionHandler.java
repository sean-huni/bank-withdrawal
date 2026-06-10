package com.example.bank.api.advice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.MissingApiVersionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.bank.dto.resp.ApiResponse;
import com.example.bank.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Spring Framework 7 native API-versioning category → 400. An unsupported or
 * missing {api-version} path segment is a client error, not a 500. Higher
 * precedence than the catch-all.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
@RequiredArgsConstructor
public class ApiVersionExceptionHandler {

	private final ErrorResponseFactory errors;

	@ExceptionHandler(InvalidApiVersionException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleInvalidApiVersion(final InvalidApiVersionException ex) {
		log.warn("Unsupported API version requested: {}", ex.getVersion());
		return errors.failure(errors.errorOf(ErrorCode.UNSUPPORTED_API_VERSION, ex.getVersion()));
	}

	@ExceptionHandler(MissingApiVersionException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleMissingApiVersion(final MissingApiVersionException ex) {
		log.warn("API version missing from request");
		return errors.failure(errors.errorOf(ErrorCode.MISSING_API_VERSION));
	}
}
