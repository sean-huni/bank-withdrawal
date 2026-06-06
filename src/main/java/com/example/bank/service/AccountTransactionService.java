package com.example.bank.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import com.example.bank.api.dto.resp.TransactionResponse;
import com.example.bank.config.CachingConfig;
import com.example.bank.domain.AccountTransaction;
import com.example.bank.domain.TransactionType;
import com.example.bank.exception.AccountNotFoundException;
import com.example.bank.exception.InsufficientFundsException;
import com.example.bank.exception.TransactionNotFoundException;
import com.example.bank.idempotency.Idempotent;
import com.example.bank.idempotency.IdempotencyKey;
import com.example.bank.jpa.model.AccountEntity;
import com.example.bank.jpa.model.TransactionEntity;
import com.example.bank.jpa.repo.AccountRepo;
import com.example.bank.jpa.repo.TransactionRepo;
import com.example.bank.mapper.TransactionMapper;

import io.micrometer.observation.annotation.Observed;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code @Validated} enforces the parameter constraints declaratively at this
 * boundary too (defense-in-depth behind the controller's {@code @Valid}); only
 * database-state invariants — account existence, sufficient funds — are
 * expressed as business exceptions.
 */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
public class AccountTransactionService {

    private final AccountRepo accountRepo;
    private final TransactionRepo transactionRepo;
    private final TransactionMapper transactionMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Atomic withdrawal in ONE statement: the native guarded UPDATE evaluates
     * the funds check inside the database and RETURNING hands back the new
     * balance — no pre-read, no re-read. The ledger row is written in the same
     * transaction; the domain event is published only after commit.
     *
     * @param accountId      account to debit
     * @param idempotencyKey reservation key — replays return the cached response
     * @param amount         positive amount, max 15 integer / 4 fraction digits
     * @return the created DEBIT transaction, ready to serve
     * @throws AccountNotFoundException   unknown account (404)
     * @throws InsufficientFundsException guard failed (422)
     */
    @Idempotent
    @Transactional
    @Observed(name = "account.withdraw")
    public TransactionResponse withdraw(@NotNull final UUID accountId,
                                        @IdempotencyKey @NotNull final UUID idempotencyKey,
                                        @NotNull @Positive @Digits(integer = 15, fraction = 4) final BigDecimal amount) {
        final BigDecimal balanceAfter = accountRepo.debit(accountId, amount)
                .orElseThrow(() -> debitRejection(accountId, amount));
        final AccountTransaction transaction = recordTransaction(
                accountId, TransactionType.DEBIT, amount, balanceAfter, idempotencyKey);
        eventPublisher.publishEvent(transaction);

        log.info("Withdrawal applied: account={} amount={} balanceAfter={} txId={}",
                accountId, amount, transaction.balanceAfter(), transaction.transactionId());
        return transactionMapper.toTransactionResponse(transaction);
    }

    /**
     * Atomic deposit: single-statement credit via RETURNING, ledger entry in
     * the same transaction. Same idempotency contract as {@link #withdraw}.
     *
     * @param accountId      account to credit
     * @param idempotencyKey reservation key — replays return the cached response
     * @param amount         positive amount, max 15 integer / 4 fraction digits
     * @return the created CREDIT transaction, ready to serve
     * @throws AccountNotFoundException unknown account (404)
     */
    @Idempotent
    @Transactional
    @Observed(name = "account.deposit")
    public TransactionResponse deposit(@NotNull final UUID accountId,
                                       @IdempotencyKey @NotNull final UUID idempotencyKey,
                                       @NotNull @Positive @Digits(integer = 15, fraction = 4) final BigDecimal amount) {
        final BigDecimal balanceAfter = accountRepo.credit(accountId, amount)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        final AccountTransaction transaction = recordTransaction(
                accountId, TransactionType.CREDIT, amount, balanceAfter, idempotencyKey);
        eventPublisher.publishEvent(transaction);

        log.info("Deposit applied: account={} amount={} balanceAfter={} txId={}",
                accountId, amount, transaction.balanceAfter(), transaction.transactionId());
        return transactionMapper.toTransactionResponse(transaction);
    }

    /**
     * Pages through the account's ledger.
     *
     * @param accountId account whose statement is requested
     * @param pageable  page/size/sort from the web layer
     * @return one page of transactions, mapped to DTOs
     * @throws AccountNotFoundException unknown account (404)
     */
    @Transactional(readOnly = true)
    @Observed(name = "account.statement")
    public Page<TransactionResponse> statement(@NotNull final UUID accountId, final Pageable pageable) {
        requireAccount(accountId);
        return transactionRepo.findByAccountId(accountId, pageable)
                .map(transactionMapper::toAccountTransaction)
                .map(transactionMapper::toTransactionResponse);
    }

    /**
     * Single ledger entry lookup. Ledger entries are write-once, so a cached
     * entry can never go stale; {@code sync} collapses concurrent stampedes on
     * one key to a single load.
     *
     * @param accountId     owning account
     * @param transactionId ledger entry id
     * @return the transaction, mapped to a DTO (possibly from cache)
     * @throws TransactionNotFoundException unknown id for this account (404)
     */
    @Transactional(readOnly = true)
    @Observed(name = "account.transaction.get")
    @Cacheable(cacheNames = CachingConfig.TRANSACTION_BY_ID,
            key = "#accountId + ':' + #transactionId", sync = true)
    public TransactionResponse findTransaction(@NotNull final UUID accountId, @NotNull final UUID transactionId) {
        return transactionRepo.findByAccountIdAndId(accountId, transactionId)
                .map(transactionMapper::toAccountTransaction)
                .map(transactionMapper::toTransactionResponse)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    private void requireAccount(final UUID accountId) {
        if (!accountRepo.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
    }

    /** Empty RETURNING result: unknown account ⇒ 404, otherwise the guard failed ⇒ 422. */
    private RuntimeException debitRejection(final UUID accountId, final BigDecimal amount) {
        if (!accountRepo.existsById(accountId)) {
            return new AccountNotFoundException(accountId);
        }
        log.warn("Withdrawal of {} from account {} rejected: insufficient funds", amount, accountId);
        return new InsufficientFundsException(accountId);
    }

    private AccountTransaction recordTransaction(final UUID accountId, final TransactionType type,
                                                 final BigDecimal amount, final BigDecimal balanceAfter,
                                                 final UUID idempotencyKey) {
        // lazy reference — getId() never triggers a SELECT on the proxy
        final AccountEntity account = accountRepo.getReferenceById(accountId);
        final var transactionEntity = TransactionEntity.create(account, type, amount, balanceAfter, idempotencyKey);
        final TransactionEntity savedTx = transactionRepo.save(transactionEntity);
        return transactionMapper.toAccountTransaction(savedTx);
    }
}
