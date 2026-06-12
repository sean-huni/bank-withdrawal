package com.example.bank.api.advice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.example.bank.dto.resp.ApiResponse;
import com.example.bank.exception.AccountNotFoundException;
import com.example.bank.exception.CardNotFoundException;
import com.example.bank.exception.ErrorCode;
import com.example.bank.exception.IdempotencyConflictException;
import com.example.bank.exception.InsufficientFundsException;
import com.example.bank.exception.PinInvalidException;
import com.example.bank.exception.TransactionNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Domain-invariant category: state-dependent business failures that surface as
 * 4xx. 404 unknown account/transaction/card and unknown path; 401 bad PIN; 409
 * idempotency clash; 422 insufficient funds. Higher precedence than the
 * catch-all so each maps to its semantic status, not 500.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
@RequiredArgsConstructor
public class BusinessExceptionHandler {

	private final ErrorResponseFactory errors;

	@ExceptionHandler(AccountNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiResponse<Void> handleAccountNotFound(final AccountNotFoundException ex) {
		log.warn("Account not found: {}", ex.getMessage());
		return errors.failure(ex);
	}

	@ExceptionHandler(TransactionNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiResponse<Void> handleTransactionNotFound(final TransactionNotFoundException ex) {
		log.warn("Transaction not found: {}", ex.getMessage());
		return errors.failure(ex);
	}

	@ExceptionHandler(CardNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiResponse<Void> handleCardNotFound(final CardNotFoundException ex) {
		// the card number is never logged — only the generic code/message
		log.warn("Card lookup failed: card not recognised");
		return errors.failure(ex);
	}

	@ExceptionHandler(PinInvalidException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public ApiResponse<Void> handlePinInvalid(final PinInvalidException ex) {
		log.warn("PIN verification failed");   // no card/PIN in the log
		return errors.failure(ex);
	}

	@ExceptionHandler(InsufficientFundsException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
	public ApiResponse<Void> handleInsufficientFunds(final InsufficientFundsException ex) {
		log.warn("Insufficient funds on account {}", ex.getAccountId());
		return errors.failure(ex);
	}

	@ExceptionHandler(IdempotencyConflictException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiResponse<Void> handleIdempotencyConflict(final IdempotencyConflictException ex) {
		log.warn("Idempotency conflict: {}", ex.getMessage());
		return errors.failure(ex);
	}

	/**
	 * Unknown paths raise {@link NoResourceFoundException}; without this handler
	 * they fall into the 500 catch-all — an unknown URL must be a 404. Grouped
	 * with the domain not-found family so all 404 mapping lives in one place.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiResponse<Void> handleNoResource(final NoResourceFoundException ex) {
		log.warn("No resource for path: {}", ex.getResourcePath());
		return errors.failure(errors.errorOf(ErrorCode.RESOURCE_NOT_FOUND));
	}
}
