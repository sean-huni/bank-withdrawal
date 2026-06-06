package com.example.bank.jdbc.model;

import java.util.UUID;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.example.bank.idempotency.IdempotencyStatus;

import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Reservation + cached response for one idempotent operation. The unique key
 * constraint — not an application lock — serializes concurrent retries.
 */
@Getter
@Table("idempotency_records")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecordEntity extends BaseEntity {

	@NotNull
	@Column("idempotency_key")
	private UUID key;

	@NotNull
	@Column("request_fingerprint")
	private String requestFingerprint;

	@NotNull
	private IdempotencyStatus status;

	@Column("response_body")
	private String responseBody;

	@Column("response_type")
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
