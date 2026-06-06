package com.example.bank.exception;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {

	public TransactionNotFoundException(final UUID transactionId) {
		super("Transaction %s not found".formatted(transactionId));
	}
}
