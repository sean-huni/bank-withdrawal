package com.example.bank.api.dto.resp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.bank.domain.TransactionType;

public record TransactionResponse(
		UUID transactionId,
		UUID accountId,
		TransactionType type,
		BigDecimal amount,
		BigDecimal balanceAfter,
		Instant occurredAt) {
}
