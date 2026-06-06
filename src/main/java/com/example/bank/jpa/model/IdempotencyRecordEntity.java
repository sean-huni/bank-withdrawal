package com.example.bank.jpa.model;

import java.util.UUID;

import com.example.bank.idempotency.IdempotencyStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Reservation + cached response for one idempotent operation. The unique key
 * constraint — not an application lock — serializes concurrent retries.
 */
@Getter
@Entity
@Table(name = "idempotency_records",
		uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_key", columnNames = "idempotency_key"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecordEntity extends BaseEntity {

	@NotNull
	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private UUID key;

	@NotNull
	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
	private String requestFingerprint;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private IdempotencyStatus status;

	@Column(name = "response_body", columnDefinition = "text")
	private String responseBody;

	@Column(name = "response_type", length = 256)
	private String responseType;

	public static IdempotencyRecordEntity started(final UUID key, final String requestFingerprint) {
		final IdempotencyRecordEntity record = new IdempotencyRecordEntity();
		record.key = key;
		record.requestFingerprint = requestFingerprint;
		record.status = IdempotencyStatus.STARTED;
		return record;
	}

	public void complete(final String responseBody, final String responseType) {
		this.responseBody = responseBody;
		this.responseType = responseType;
		this.status = IdempotencyStatus.COMPLETED;
	}
}
