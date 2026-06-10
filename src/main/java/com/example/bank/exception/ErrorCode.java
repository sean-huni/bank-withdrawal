package com.example.bank.exception;

/**
 * Single source of truth binding each WIRE code (the stable {@code error.code}
 * clients and tests assert on) to its message-bundle key. Several constants may
 * share one wire code while keying different messages (the idempotency trio).
 * Pure Java on purpose — usable from services and aspects without Spring.
 */
public enum ErrorCode {

	ACCOUNT_NOT_FOUND("error.account.not-found"),
	CARD_NOT_FOUND("error.card.not-found"),
	PIN_INVALID("error.pin.invalid"),
	TRANSACTION_NOT_FOUND("error.transaction.not-found"),
	INSUFFICIENT_FUNDS("error.funds.insufficient"),
	IDEMPOTENCY_UNRESOLVED("IDEMPOTENCY_CONFLICT", "error.idempotency.unresolved"),
	IDEMPOTENCY_REPLAY_MISMATCH("IDEMPOTENCY_CONFLICT", "error.idempotency.replay-mismatch"),
	IDEMPOTENCY_IN_PROGRESS("IDEMPOTENCY_CONFLICT", "error.idempotency.in-progress"),
	VALIDATION_FAILED("error.validation.failed"),
	MISSING_HEADER("error.header.missing"),
	INVALID_PARAMETER("error.parameter.invalid"),
	UNSUPPORTED_API_VERSION("error.api-version.unsupported"),
	MISSING_API_VERSION("error.api-version.missing"),
	MALFORMED_BODY("error.body.malformed"),
	CONCURRENT_MODIFICATION("error.concurrency.conflict"),
	DATA_INTEGRITY_VIOLATION("error.data.integrity"),
	RESOURCE_NOT_FOUND("error.resource.not-found"),
	UNAUTHORIZED("error.auth.unauthorized"),
	FORBIDDEN("error.auth.forbidden"),
	INTERNAL_ERROR("error.internal");

	private final String wireCode;
	private final String messageKey;

	ErrorCode(final String messageKey) {
		this.wireCode = name();
		this.messageKey = messageKey;
	}

	ErrorCode(final String wireCode, final String messageKey) {
		this.wireCode = wireCode;
		this.messageKey = messageKey;
	}

	public String wireCode() {
		return wireCode;
	}

	public String messageKey() {
		return messageKey;
	}
}
