package com.example.bank.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A completed ledger movement — the domain object exchanged between the
 * service and the outer layers (mapped to dto/event, never exposed raw).
 * Also the payload cached by the idempotency aspect for replays.
 */
public record AccountTransaction(
		UUID transactionId,
		UUID accountId,
		TransactionType type,
		BigDecimal amount,
		BigDecimal balanceAfter,
		Instant occurredAt) {
}
