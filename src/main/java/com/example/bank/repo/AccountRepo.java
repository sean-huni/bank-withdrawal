package com.example.bank.repo;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import com.example.bank.model.AccountEntity;

/**
 * Accounts are mutated EXCLUSIVELY via the guarded atomic UPDATEs below, which
 * bump {@code version} in SQL. Never load-and-{@code save()} an
 * {@code AccountEntity} — the in-memory version would be stale and the
 * framework's optimistic-lock check would throw on save.
 */
public interface AccountRepo extends CrudRepository<AccountEntity, UUID> {

	/**
	 * Atomic guarded debit in ONE roundtrip — the {@code balance >= :amount}
	 * predicate is evaluated by the database (concurrent withdrawals cannot
	 * overdraw) and {@code RETURNING} hands back the new balance, eliminating
	 * any pre-read or re-read. Empty result ⇒ guard failed (insufficient
	 * funds) or unknown account — the caller disambiguates.
	 */
	@Query("""
			UPDATE accounts
			   SET balance = balance - :amount,
			       version = version + 1,
			       updated_at = now()
			 WHERE id = :accountId
			   AND balance >= :amount
			RETURNING balance
			""")
	Optional<BigDecimal> debit(@Param("accountId") UUID accountId, @Param("amount") BigDecimal amount);

	@Query("""
			UPDATE accounts
			   SET balance = balance + :amount,
			       version = version + 1,
			       updated_at = now()
			 WHERE id = :accountId
			RETURNING balance
			""")
	Optional<BigDecimal> credit(@Param("accountId") UUID accountId, @Param("amount") BigDecimal amount);
}
