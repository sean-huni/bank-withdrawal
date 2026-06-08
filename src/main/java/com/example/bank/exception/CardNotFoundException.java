package com.example.bank.exception;

/** The supplied card number matched no account. The card number is intentionally NOT carried as a message arg. */
public class CardNotFoundException extends ApiException {

	public CardNotFoundException() {
		super(ErrorCode.CARD_NOT_FOUND);
	}
}
