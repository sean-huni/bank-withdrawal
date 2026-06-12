package com.example.bank.api.advice;

import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.bank.dto.resp.ApiError;
import com.example.bank.dto.resp.ApiResponse;
import com.example.bank.exception.ErrorCode;

import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Input-validation and malformed-request category → 400. Covers bean
 * validation ({@code @Valid}), {@code @Validated} method validation,
 * Spring's handler-method validation (the sort whitelist), plus the
 * request-binding failures (missing header, type mismatch, unreadable body).
 * Highest precedence so these win over the catch-all.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
@RequiredArgsConstructor
public class ValidationExceptionHandler {

	private final ErrorResponseFactory errors;

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleValidation(final MethodArgumentNotValidException ex) {
		final List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
				.map(errors::toViolation)
				.toList();
		log.warn("Request validation failed: {}", violations);
		return errors.validationFailure(violations);
	}

	/**
	 * Thrown by {@code @Validated} method validation at the service boundary —
	 * defense-in-depth behind the controller's {@code @Valid}.
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleConstraintViolation(final ConstraintViolationException ex) {
		final List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
				.map(errors::toViolation)
				.toList();
		log.warn("Constraint violations at service boundary: {}", violations);
		return errors.validationFailure(violations);
	}

	/**
	 * Thrown by Spring's built-in handler method validation when a constraint
	 * sits directly on a controller parameter (e.g. the statement endpoint's
	 * sort whitelist on {@code Pageable}) — the modern counterpart of
	 * {@link ConstraintViolationException}, which built-in validation does NOT
	 * throw. Unhandled it would fall into the 500 catch-all.
	 */
	@ExceptionHandler(HandlerMethodValidationException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleHandlerMethodValidation(final HandlerMethodValidationException ex) {
		final List<ApiError.FieldViolation> violations = ex.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> errors.toViolation(result, error)))
				.toList();
		log.warn("Handler method validation failed: {}", violations);
		return errors.validationFailure(violations);
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleMissingHeader(final MissingRequestHeaderException ex) {
		log.warn("Missing required header: {}", ex.getHeaderName());
		return errors.failure(errors.errorOf(ErrorCode.MISSING_HEADER, ex.getHeaderName()));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleTypeMismatch(final MethodArgumentTypeMismatchException ex) {
		log.warn("Invalid parameter '{}': {}", ex.getName(), ex.getMessage());
		return errors.failure(errors.errorOf(ErrorCode.INVALID_PARAMETER, ex.getName()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleUnreadable(final HttpMessageNotReadableException ex) {
		log.warn("Malformed request body: {}", ex.getMessage());
		return errors.failure(errors.errorOf(ErrorCode.MALFORMED_BODY));
	}
}
