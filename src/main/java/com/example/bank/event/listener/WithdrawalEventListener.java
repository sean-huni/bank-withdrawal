package com.example.bank.event.listener;

import com.example.bank.domain.AccountTransaction;
import com.example.bank.domain.TransactionType;
import com.example.bank.event.publisher.WithdrawalEventPublisher;
import com.example.bank.mapper.TransactionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges the in-process domain event to the outbound port AFTER the
 * transaction commits — no event is emitted for rolled-back operations, and
 * idempotent replays never re-publish (the service is not re-executed).
 * Production-grade guaranteed delivery would use a transactional outbox; the
 * after-commit listener is the documented lighter trade-off.
 */
@Component
@RequiredArgsConstructor
public class WithdrawalEventListener {

	private final WithdrawalEventPublisher withdrawalEventPublisher;
	private final TransactionMapper transactionMapper;


	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onTransaction(final AccountTransaction transaction) {
		if (transaction.type() == TransactionType.DEBIT) {
			withdrawalEventPublisher.publish(transactionMapper.toWithdrawalEvent(transaction));
		}
	}
}
