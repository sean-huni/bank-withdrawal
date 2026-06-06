package com.example.bank.exception;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {

	public AccountNotFoundException(final UUID accountId) {
		super("Account %s not found".formatted(accountId));
	}
}
