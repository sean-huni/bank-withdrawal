package com.example.bank.exception;

import java.util.UUID;

/** One wire code (IDEMPOTENCY_CONFLICT), three distinct localized messages. */
public class IdempotencyConflictException extends ApiException {

	public IdempotencyConflictException(final ErrorCode errorCode, final UUID idempotencyKey) {
		super(errorCode, idempotencyKey);
	}
}
