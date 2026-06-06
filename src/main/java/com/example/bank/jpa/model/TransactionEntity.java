package com.example.bank.jpa.model;

import java.math.BigDecimal;
import java.util.UUID;

import com.example.bank.domain.TransactionType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Immutable ledger entry — write-once, no setters, static factory only.
 * The unique idempotency key is a second line of defence behind the
 * idempotency_records reservation.
 */
@Getter
@Entity
@Table(name = "transactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionEntity extends BaseEntity {

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16, updatable = false)
	private TransactionType type;

	@NotNull
	@Positive
	@Column(nullable = false, precision = 19, scale = 4, updatable = false)
	private BigDecimal amount;

	@NotNull
	@Column(name = "balance_after", nullable = false, precision = 19, scale = 4, updatable = false)
	private BigDecimal balanceAfter;

	@NotNull
	@Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
	private UUID idempotencyKey;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false, updatable = false)
	private AccountEntity account;

	public static TransactionEntity create(final AccountEntity account, final TransactionType type,
			final BigDecimal amount, final BigDecimal balanceAfter, final UUID idempotencyKey) {
		final TransactionEntity transaction = new TransactionEntity();
		transaction.account = account;
		transaction.type = type;
		transaction.amount = amount;
		transaction.balanceAfter = balanceAfter;
		transaction.idempotencyKey = idempotencyKey;
		return transaction;
	}
}
