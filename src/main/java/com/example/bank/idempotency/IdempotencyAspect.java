package com.example.bank.idempotency;

import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.bank.exception.IdempotencyConflictException;
import com.example.bank.model.IdempotencyRecordEntity;
import com.example.bank.repo.IdempotencyRecordRepo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Wraps {@code @Idempotent} methods. Runs at HIGHEST_PRECEDENCE so it sits
 * outside the {@code @Transactional} advisor and opens the transaction itself
 * via {@link TransactionTemplate} — the reservation, the business mutation and
 * the cached response then commit atomically, and the duplicate-key
 * {@link DataIntegrityViolationException} is caught outside the (rolled-back)
 * transaction so the replay can be served from a fresh read.
 *
 * <p>Concurrency: two retries with the same key race on the INSERT;
 * the second blocks on the unique index until the first settles. Commit ⇒
 * duplicate-key ⇒ replay. Rollback ⇒ the row disappears ⇒ the second proceeds.
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class IdempotencyAspect {

	private final IdempotencyRecordRepo idempotencyRecordRepo;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper;

	@Around("@annotation(com.example.bank.idempotency.Idempotent)")
	public Object apply(final ProceedingJoinPoint joinPoint) {
		final UUID key = resolveKey(joinPoint);
		final String fingerprint = fingerprint(joinPoint);
		final Class<?> returnType = ((MethodSignature) joinPoint.getSignature()).getReturnType();

		try {
			return transactionTemplate.execute(status -> {
				// Data JDBC writes immediately — this INSERT claims the unique key now
				final IdempotencyRecordEntity record = idempotencyRecordRepo.save(
						IdempotencyRecordEntity.started(key, fingerprint));
				final Object result = proceed(joinPoint);                   // business op joins this tx
				record.complete(serialize(result), returnType.getName());
				idempotencyRecordRepo.save(record);                         // versioned UPDATE → COMPLETED
				return result;
			});
		} catch (final DataIntegrityViolationException duplicate) {
			return replay(key, fingerprint, returnType);
		}
	}

	private Object replay(final UUID key, final String fingerprint, final Class<?> returnType) {
		final IdempotencyRecordEntity existing = idempotencyRecordRepo.findByKey(key)
				.orElseThrow(() -> new IdempotencyConflictException(
						"Idempotency-Key %s could not be resolved".formatted(key)));

		if (!existing.getRequestFingerprint().equals(fingerprint)) {
			throw new IdempotencyConflictException(
					"Idempotency-Key %s was already used with a different request".formatted(key));
		}
		if (existing.getStatus() != IdempotencyStatus.COMPLETED) {
			throw new IdempotencyConflictException(
					"A request with Idempotency-Key %s is already in progress".formatted(key));
		}
		log.info("Replaying idempotent response for key={}", key);
		return deserialize(existing.getResponseBody(), returnType);
	}

	private UUID resolveKey(final ProceedingJoinPoint joinPoint) {
		final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
		final Parameter[] parameters = signature.getMethod().getParameters();
		final Object[] args = joinPoint.getArgs();
		for (int i = 0; i < parameters.length; i++) {
			if (parameters[i].isAnnotationPresent(IdempotencyKey.class)) {
				return (UUID) args[i];
			}
		}
		throw new IllegalStateException(
				"@Idempotent method %s must declare a UUID parameter annotated with @IdempotencyKey"
						.formatted(signature.getName()));
	}

	/** SHA-256 over the method identity + all non-key arguments. */
	private String fingerprint(final ProceedingJoinPoint joinPoint) {
		final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
		final Parameter[] parameters = signature.getMethod().getParameters();
		final Object[] args = joinPoint.getArgs();
		final List<Object> material = new ArrayList<>();
		material.add(signature.toLongString());
		for (int i = 0; i < parameters.length; i++) {
			if (!parameters[i].isAnnotationPresent(IdempotencyKey.class)) {
				material.add(args[i]);
			}
		}
		return sha256Hex(serialize(material));
	}

	private String serialize(final Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (final Exception e) {
			throw new IllegalStateException("Failed to serialize idempotent payload", e);
		}
	}

	private Object deserialize(final String json, final Class<?> type) {
		try {
			return objectMapper.readValue(json, type);
		} catch (final Exception e) {
			throw new IllegalStateException("Failed to deserialize cached idempotent response", e);
		}
	}

	private String sha256Hex(final String value) {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private Object proceed(final ProceedingJoinPoint joinPoint) {
		try {
			return joinPoint.proceed();
		} catch (final RuntimeException | Error e) {
			throw e; // business failure → TransactionTemplate rolls back, key freed
		} catch (final Throwable t) {
			throw new IllegalStateException("Idempotent invocation failed", t);
		}
	}
}
