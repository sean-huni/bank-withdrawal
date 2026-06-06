package com.example.bank.model;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.example.bank.domain.TransactionType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Immutable ledger entry — write-once, no setters, static factory only.
 * The unique idempotency key is a second line of defence behind the
 * idempotency_records reservation. Data JDBC flavour: the owning account is
 * referenced by id (aggregates link by key, not by object graph).
 */
@Getter
@Table("transactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionEntity extends BaseEntity {

	@NotNull
	private TransactionType type;

	@NotNull
	@Positive
	private BigDecimal amount;

	@NotNull
	@Column("balance_after")
	private BigDecimal balanceAfter;

	@NotNull
	@Column("idempotency_key")
	private UUID idempotencyKey;

	@NotNull
	@Column("account_id")
	private UUID accountId;

	public static TransactionEntity create(final UUID accountId, final TransactionType type,
			final BigDecimal amount, final BigDecimal balanceAfter, final UUID idempotencyKey) {
		final TransactionEntity transaction = new TransactionEntity();
		transaction.accountId = accountId;
		transaction.type = type;
		transaction.amount = amount;
		transaction.balanceAfter = balanceAfter;
		transaction.idempotencyKey = idempotencyKey;
		return transaction;
	}
}
