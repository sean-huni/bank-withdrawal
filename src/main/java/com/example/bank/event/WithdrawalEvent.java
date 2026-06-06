package com.example.bank.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Integration event published to SNS — replaces the original snippet's
 * hand-rolled toJson(); serialized with Jackson. The eventId enables
 * idempotent consumption downstream.
 */
public record WithdrawalEvent(
		UUID eventId,
		UUID accountId,
		BigDecimal amount,
		String status,
		Instant occurredAt) {
}
