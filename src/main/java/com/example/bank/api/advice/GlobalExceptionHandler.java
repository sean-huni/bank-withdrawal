package com.example.bank.api.advice;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.MissingApiVersionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.example.bank.api.TraceIdProvider;
import com.example.bank.api.dto.resp.ApiError;
import com.example.bank.api.dto.resp.ApiResponse;
import com.example.bank.exception.AccountNotFoundException;
import com.example.bank.exception.IdempotencyConflictException;
import com.example.bank.exception.InsufficientFundsException;
import com.example.bank.exception.TransactionNotFoundException;

import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Centralized error handling: every error path maps to a status via
 * {@code @ResponseStatus} and a uniform {@link ApiResponse} envelope carrying
 * a stable error code and the current trace id. Status semantics: 404 unknown
 * resource; 422 well-formed request violating a business rule; 409 idempotency
 * clashes, optimistic-lock and integrity conflicts; 400 malformed input or
 * missing headers; 500 non-leaking catch-all.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

	private final TraceIdProvider traceIdProvider;

	@ExceptionHandler(AccountNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiResponse<Void> handleAccountNotFound(final AccountNotFoundException ex) {
		log.warn("Account not found: {}", ex.getMessage());
		return failure(ApiError.of("ACCOUNT_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(TransactionNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiResponse<Void> handleTransactionNotFound(final TransactionNotFoundException ex) {
		log.warn("Transaction not found: {}", ex.getMessage());
		return failure(ApiError.of("TRANSACTION_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(InsufficientFundsException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
	public ApiResponse<Void> handleInsufficientFunds(final InsufficientFundsException ex) {
		log.warn("Insufficient funds on account {}", ex.getAccountId());
		return failure(ApiError.of("INSUFFICIENT_FUNDS", ex.getMessage()));
	}

	@ExceptionHandler(IdempotencyConflictException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiResponse<Void> handleIdempotencyConflict(final IdempotencyConflictException ex) {
		log.warn("Idempotency conflict: {}", ex.getMessage());
		return failure(ApiError.of("IDEMPOTENCY_CONFLICT", ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleValidation(final MethodArgumentNotValidException ex) {
		final List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> new ApiError.FieldViolation(fieldError.getField(), fieldError.getDefaultMessage()))
				.toList();
		log.warn("Request validation failed: {}", violations);
		return failure(new ApiError("VALIDATION_FAILED", "Request validation failed", violations));
	}

	/**
	 * Thrown by {@code @Validated} method validation at the service boundary —
	 * defense-in-depth behind the controller's {@code @Valid}.
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleConstraintViolation(final ConstraintViolationException ex) {
		final List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
				.map(violation -> new ApiError.FieldViolation(
						leafProperty(violation.getPropertyPath().toString()), violation.getMessage()))
				.toList();
		log.warn("Constraint violations at service boundary: {}", violations);
		return failure(new ApiError("VALIDATION_FAILED", "Request validation failed", violations));
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleMissingHeader(final MissingRequestHeaderException ex) {
		log.warn("Missing required header: {}", ex.getHeaderName());
		return failure(ApiError.of("MISSING_HEADER",
				"Required header '%s' is missing".formatted(ex.getHeaderName())));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleTypeMismatch(final MethodArgumentTypeMismatchException ex) {
		log.warn("Invalid parameter '{}': {}", ex.getName(), ex.getMessage());
		return failure(ApiError.of("INVALID_PARAMETER",
				"Parameter '%s' has an invalid value".formatted(ex.getName())));
	}

	/** Spring Framework 7 native API versioning — unsupported {version} path segment. */
	@ExceptionHandler(InvalidApiVersionException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleInvalidApiVersion(final InvalidApiVersionException ex) {
		log.warn("Unsupported API version requested: {}", ex.getVersion());
		return failure(ApiError.of("UNSUPPORTED_API_VERSION",
				"API version '%s' is not supported".formatted(ex.getVersion())));
	}

	@ExceptionHandler(MissingApiVersionException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleMissingApiVersion(final MissingApiVersionException ex) {
		log.warn("API version missing from request");
		return failure(ApiError.of("MISSING_API_VERSION", "An API version is required"));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleUnreadable(final HttpMessageNotReadableException ex) {
		log.warn("Malformed request body: {}", ex.getMessage());
		return failure(ApiError.of("MALFORMED_BODY", "Malformed request body"));
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiResponse<Void> handleOptimisticLock(final ObjectOptimisticLockingFailureException ex) {
		log.warn("Concurrent modification: {}", ex.getMessage());
		return failure(ApiError.of("CONCURRENT_MODIFICATION",
				"The resource was modified concurrently; retry"));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiResponse<Void> handleDataIntegrity(final DataIntegrityViolationException ex) {
		log.warn("Data integrity violation: {}", ex.getMessage());
		return failure(ApiError.of("DATA_INTEGRITY_VIOLATION", "The request conflicts with existing data"));
	}

	/**
	 * Unknown paths raise {@link NoResourceFoundException}; without this handler
	 * they fall into the 500 catch-all — an unknown URL must be a 404.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiResponse<Void> handleNoResource(final NoResourceFoundException ex) {
		log.warn("No resource for path: {}", ex.getResourcePath());
		return failure(ApiError.of("RESOURCE_NOT_FOUND", "No resource at the requested path"));
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ApiResponse<Void> handleUnexpected(final Exception ex) {
		log.error("Unhandled exception", ex);
		return failure(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred"));
	}

	private ApiResponse<Void> failure(final ApiError error) {
		return ApiResponse.failure(error, traceIdProvider.currentTraceId());
	}

	/** "withdraw.amount" → "amount" — clients see the field, not the method path. */
	private String leafProperty(final String propertyPath) {
		final int lastDot = propertyPath.lastIndexOf('.');
		return lastDot < 0 ? propertyPath : propertyPath.substring(lastDot + 1);
	}
}
