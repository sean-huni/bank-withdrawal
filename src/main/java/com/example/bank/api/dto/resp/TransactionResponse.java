package com.example.bank.api.dto.resp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.bank.domain.TransactionType;

import io.swagger.v3.oas.annotations.media.Schema;

/** Complete representation of one immutable ledger entry. */
@Schema(description = "One immutable ledger entry; the full resource — no follow-up read needed")
public record TransactionResponse(
		@Schema(description = "Ledger entry id")
		UUID transactionId,
		@Schema(description = "Owning account")
		UUID accountId,
		@Schema(description = "Accounting direction: DEBIT (withdrawal) or CREDIT (deposit)")
		TransactionType type,
		@Schema(description = "Amount moved", example = "250.00")
		BigDecimal amount,
		@Schema(description = "Account balance immediately after this transaction", example = "750.00")
		BigDecimal balanceAfter,
		@Schema(description = "When the transaction was recorded (UTC)")
		Instant occurredAt) {
}
