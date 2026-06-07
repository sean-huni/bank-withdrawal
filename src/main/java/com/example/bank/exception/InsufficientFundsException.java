package com.example.bank.exception;

import java.util.UUID;

public class InsufficientFundsException extends ApiException {

	private final UUID accountId;

	public InsufficientFundsException(final UUID accountId) {
		// deliberately arg-free: the message must not leak the account id (it is
		// logged, not echoed) — keep the bundle entry placeholder-free too
		super(ErrorCode.INSUFFICIENT_FUNDS);
		this.accountId = accountId;
	}

	public UUID getAccountId() {
		return accountId;
	}
}
