package com.example.bank.api.advice;

import java.util.List;

import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;

import com.example.bank.config.TraceIdProvider;
import com.example.bank.dto.resp.ApiError;
import com.example.bank.dto.resp.ApiResponse;
import com.example.bank.exception.ApiException;
import com.example.bank.exception.ErrorCode;

import jakarta.validation.ConstraintViolation;
import lombok.RequiredArgsConstructor;

/**
 * Single collaborator shared by the categorised {@code @RestControllerAdvice}
 * handlers: envelope construction, i18n message resolution and the
 * violation-to-payload mapping. Extracting it keeps each handler small and one
 * responsibility, and guarantees the wire codes, error shape and i18n
 * resolution are byte-identical regardless of which category produced the
 * error. Package-private — only the advice package consumes it.
 */
@Component
@RequiredArgsConstructor
class ErrorResponseFactory {

	private final TraceIdProvider traceIdProvider;
	private final MessageSource messageSource;

	/** Business exception → envelope: wire code from the enum, text from the bundle. */
	ApiResponse<Void> failure(final ApiException ex) {
		return failure(errorOf(ex.getErrorCode(), ex.getArgs()));
	}

	ApiResponse<Void> failure(final ApiError error) {
		return ApiResponse.failure(error, traceIdProvider.currentTraceId());
	}

	ApiError errorOf(final ErrorCode errorCode, final Object... args) {
		return ApiError.of(errorCode.wireCode(), resolve(errorCode, args));
	}

	ApiResponse<Void> validationFailure(final List<ApiError.FieldViolation> violations) {
		return failure(new ApiError(ErrorCode.VALIDATION_FAILED.wireCode(),
				resolve(ErrorCode.VALIDATION_FAILED), violations));
	}

	ApiError.FieldViolation toViolation(final FieldError fieldError) {
		return new ApiError.FieldViolation(fieldError.getField(), constraintKeyOf(fieldError),
				fieldError.getDefaultMessage(), truncate(fieldError.getRejectedValue()));
	}

	ApiError.FieldViolation toViolation(final ConstraintViolation<?> violation) {
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
	ApiError.FieldViolation toViolation(final ParameterValidationResult result,
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
