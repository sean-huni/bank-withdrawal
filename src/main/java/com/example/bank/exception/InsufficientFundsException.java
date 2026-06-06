package com.example.bank.exception;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

	private final UUID accountId;

	public InsufficientFundsException(final UUID accountId) {
		// Preserves the original snippet's business message verbatim.
		super("Insufficient funds for withdrawal");
		this.accountId = accountId;
	}

	public UUID getAccountId() {
		return accountId;
	}
}
