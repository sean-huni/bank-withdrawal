package com.example.bank.exception;

import java.util.Arrays;

/**
 * Base for all business exceptions: carries the {@link ErrorCode} (wire code +
 * bundle key) and the message arguments. Localization happens ONLY in the
 * advice; {@code getMessage()} stays code-based English for logs — log lines
 * are operator-facing and never localized.
 */
public abstract class ApiException extends RuntimeException {

	private final ErrorCode errorCode;
	private final Object[] args;

	protected ApiException(final ErrorCode errorCode, final Object... args) {
		super("%s %s".formatted(errorCode.name(), Arrays.toString(args)));
		this.errorCode = errorCode;
		this.args = args.clone();
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	public Object[] getArgs() {
		return args.clone();
	}
}
