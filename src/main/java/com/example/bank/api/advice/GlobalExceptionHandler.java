package com.example.bank.api.advice;

import java.util.List;

import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.MissingApiVersionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.example.bank.config.TraceIdProvider;
import com.example.bank.dto.resp.ApiError;
import com.example.bank.dto.resp.ApiResponse;
import com.example.bank.exception.AccountNotFoundException;
import com.example.bank.exception.ApiException;
import com.example.bank.exception.CardNotFoundException;
import com.example.bank.exception.ErrorCode;
import com.example.bank.exception.IdempotencyConflictException;
import com.example.bank.exception.InsufficientFundsException;
import com.example.bank.exception.PinInvalidException;
import com.example.bank.exception.TransactionNotFoundException;

import jakarta.validation.ConstraintViolation;
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
	private final MessageSource messageSource;

	@ExceptionHandler(AccountNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiResponse<Void> handleAccountNotFound(final AccountNotFoundException ex) {
		log.warn("Account not found: {}", ex.getMessage());
		return failure(ex);
	}

	@ExceptionHandler(TransactionNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiResponse<Void> handleTransactionNotFound(final TransactionNotFoundException ex) {
		log.warn("Transaction not found: {}", ex.getMessage());
		return failure(ex);
	}

	@ExceptionHandler(CardNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiResponse<Void> handleCardNotFound(final CardNotFoundException ex) {
		// the card number is never logged — only the generic code/message
		log.warn("Card lookup failed: card not recognised");
		return failure(ex);
	}

	@ExceptionHandler(PinInvalidException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public ApiResponse<Void> handlePinInvalid(final PinInvalidException ex) {
		log.warn("PIN verification failed");   // no card/PIN in the log
		return failure(ex);
	}

	@ExceptionHandler(InsufficientFundsException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
	public ApiResponse<Void> handleInsufficientFunds(final InsufficientFundsException ex) {
		log.warn("Insufficient funds on account {}", ex.getAccountId());
		return failure(ex);
	}

	@ExceptionHandler(IdempotencyConflictException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiResponse<Void> handleIdempotencyConflict(final IdempotencyConflictException ex) {
		log.warn("Idempotency conflict: {}", ex.getMessage());
		return failure(ex);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleValidation(final MethodArgumentNotValidException ex) {
		final List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
				.map(this::toViolation)
				.toList();
		log.warn("Request validation failed: {}", violations);
		return failure(validationError(violations));
	}

	/**
	 * Thrown by {@code @Validated} method validation at the service boundary —
	 * defense-in-depth behind the controller's {@code @Valid}.
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleConstraintViolation(final ConstraintViolationException ex) {
		final List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
				.map(this::toViolation)
				.toList();
		log.warn("Constraint violations at service boundary: {}", violations);
		return failure(validationError(violations));
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
						.map(error -> toViolation(result, error)))
				.toList();
		log.warn("Handler method validation failed: {}", violations);
		return failure(validationError(violations));
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleMissingHeader(final MissingRequestHeaderException ex) {
		log.warn("Missing required header: {}", ex.getHeaderName());
		return failure(errorOf(ErrorCode.MISSING_HEADER, ex.getHeaderName()));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleTypeMismatch(final MethodArgumentTypeMismatchException ex) {
		log.warn("Invalid parameter '{}': {}", ex.getName(), ex.getMessage());
		return failure(errorOf(ErrorCode.INVALID_PARAMETER, ex.getName()));
	}

	/** Spring Framework 7 native API versioning — unsupported {version} path segment. */
	@ExceptionHandler(InvalidApiVersionException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleInvalidApiVersion(final InvalidApiVersionException ex) {
		log.warn("Unsupported API version requested: {}", ex.getVersion());
		return failure(errorOf(ErrorCode.UNSUPPORTED_API_VERSION, ex.getVersion()));
	}

	@ExceptionHandler(MissingApiVersionException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleMissingApiVersion(final MissingApiVersionException ex) {
		log.warn("API version missing from request");
		return failure(errorOf(ErrorCode.MISSING_API_VERSION));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleUnreadable(final HttpMessageNotReadableException ex) {
		log.warn("Malformed request body: {}", ex.getMessage());
		return failure(errorOf(ErrorCode.MALFORMED_BODY));
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiResponse<Void> handleOptimisticLock(final OptimisticLockingFailureException ex) {
		log.warn("Concurrent modification: {}", ex.getMessage());
		return failure(errorOf(ErrorCode.CONCURRENT_MODIFICATION));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiResponse<Void> handleDataIntegrity(final DataIntegrityViolationException ex) {
		log.warn("Data integrity violation: {}", ex.getMessage());
		return failure(errorOf(ErrorCode.DATA_INTEGRITY_VIOLATION));
	}

	/**
	 * Unknown paths raise {@link NoResourceFoundException}; without this handler
	 * they fall into the 500 catch-all — an unknown URL must be a 404.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiResponse<Void> handleNoResource(final NoResourceFoundException ex) {
		log.warn("No resource for path: {}", ex.getResourcePath());
		return failure(errorOf(ErrorCode.RESOURCE_NOT_FOUND));
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ApiResponse<Void> handleUnexpected(final Exception ex) {
		log.error("Unhandled exception", ex);
		return failure(errorOf(ErrorCode.INTERNAL_ERROR));
	}

	private ApiResponse<Void> failure(final ApiError error) {
		return ApiResponse.failure(error, traceIdProvider.currentTraceId());
	}

	/** Business exception → envelope: wire code from the enum, text from the bundle. */
	private ApiResponse<Void> failure(final ApiException ex) {
		return failure(errorOf(ex.getErrorCode(), ex.getArgs()));
	}

	private ApiError errorOf(final ErrorCode errorCode, final Object... args) {
		return ApiError.of(errorCode.wireCode(), resolve(errorCode, args));
	}

	private ApiError validationError(final List<ApiError.FieldViolation> violations) {
		return new ApiError(ErrorCode.VALIDATION_FAILED.wireCode(),
				resolve(ErrorCode.VALIDATION_FAILED), violations);
	}

	private ApiError.FieldViolation toViolation(final FieldError fieldError) {
		return new ApiError.FieldViolation(fieldError.getField(), constraintKeyOf(fieldError),
				fieldError.getDefaultMessage(), truncate(fieldError.getRejectedValue()));
	}

	private ApiError.FieldViolation toViolation(final ConstraintViolation<?> violation) {
		return new ApiError.FieldViolation(leafProperty(violation.getPropertyPath().toString()),
				keyOf(violation.getMessageTemplate()), violation.getMessage(),
				truncate(violation.getInvalidValue()));
	}

	/**
	 * A violation with a property node beyond the parameter (e.g. the sort
	 * whitelist's {@code addPropertyNode("sort")}) surfaces as a
	 * {@link FieldError}; plain parameter violations only carry the parameter
	 * name and the rejected argument.
	 */
	private ApiError.FieldViolation toViolation(final ParameterValidationResult result,
			final MessageSourceResolvable error) {
		if (error instanceof FieldError fieldError) {
			return toViolation(fieldError);
		}
		return new ApiError.FieldViolation(result.getMethodParameter().getParameterName(),
				parameterKeyOf(result, error), error.getDefaultMessage(), truncate(result.getArgument()));
	}

	/** "{error.amount.positive}" → "error.amount.positive"; non-key templates → null. */
	private String keyOf(final String messageTemplate) {
		return messageTemplate != null && messageTemplate.startsWith("{error.") && messageTemplate.endsWith("}")
				? messageTemplate.substring(1, messageTemplate.length() - 1)
				: null;
	}

	private String constraintKeyOf(final FieldError fieldError) {
		try {
			return keyOf(fieldError.unwrap(ConstraintViolation.class).getMessageTemplate());
		} catch (final IllegalArgumentException notBackedByAConstraint) {
			return null;
		}
	}

	/** Plain parameter violations don't surface as FieldError — recover the key via the result. */
	private String parameterKeyOf(final ParameterValidationResult result, final MessageSourceResolvable error) {
		try {
			return keyOf(result.unwrap(error, ConstraintViolation.class).getMessageTemplate());
		} catch (final IllegalArgumentException notBackedByAConstraint) {
			return null;
		}
	}

	/** Control chars are scrubbed — the rejected value is user input and reaches the logs. */
	private String truncate(final Object rejectedValue) {
		if (rejectedValue == null) {
			return null;
		}
		final String text = rejectedValue.toString().replaceAll("\\p{Cntrl}", " ");
		return text.length() <= 100 ? text : text.substring(0, 100);
	}

	/**
	 * THROWING overload on purpose — a missing key must never degrade to a
	 * silent default; the catalog completeness test makes it unreachable.
	 */
	private String resolve(final ErrorCode errorCode, final Object... args) {
		return messageSource.getMessage(errorCode.messageKey(), args, LocaleContextHolder.getLocale());
	}

	/** "withdraw.amount" → "amount" — clients see the field, not the method path. */
	private String leafProperty(final String propertyPath) {
		final int lastDot = propertyPath.lastIndexOf('.');
		return lastDot < 0 ? propertyPath : propertyPath.substring(lastDot + 1);
	}
}
