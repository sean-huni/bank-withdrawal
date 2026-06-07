package com.example.bank.exception;

import java.util.UUID;

public class AccountNotFoundException extends ApiException {

	public AccountNotFoundException(final UUID accountId) {
		super(ErrorCode.ACCOUNT_NOT_FOUND, accountId);
	}
}
