package com.example.bank.exception;

/** PIN did not match the stored hash. Carries no PIN/card data — never leak the secret. */
public class PinInvalidException extends ApiException {

	public PinInvalidException() {
		super(ErrorCode.PIN_INVALID);
	}
}
