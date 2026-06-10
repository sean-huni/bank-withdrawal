package com.example.bank.api.advice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.bank.dto.resp.ApiResponse;
import com.example.bank.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Data-access category → 409. Optimistic-lock failures (caught via the
 * portable {@link OptimisticLockingFailureException}, not the ORM subclass, so
 * it works on a JDBC classpath) and integrity-constraint violations both map to
 * a conflict the client can retry. Higher precedence than the catch-all.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
@RequiredArgsConstructor
public class PersistenceExceptionHandler {

	private final ErrorResponseFactory errors;

	@ExceptionHandler(OptimisticLockingFailureException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiResponse<Void> handleOptimisticLock(final OptimisticLockingFailureException ex) {
		log.warn("Concurrent modification: {}", ex.getMessage());
		return errors.failure(errors.errorOf(ErrorCode.CONCURRENT_MODIFICATION));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiResponse<Void> handleDataIntegrity(final DataIntegrityViolationException ex) {
		log.warn("Data integrity violation: {}", ex.getMessage());
		return errors.failure(errors.errorOf(ErrorCode.DATA_INTEGRITY_VIOLATION));
	}
}
