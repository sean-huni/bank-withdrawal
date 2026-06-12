package com.example.bank.exception;

import java.util.UUID;

public class TransactionNotFoundException extends ApiException {

	public TransactionNotFoundException(final UUID transactionId) {
		super(ErrorCode.TRANSACTION_NOT_FOUND, transactionId);
	}
}
