package com.example.bank.jpa.repo;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.bank.jpa.model.AccountEntity;

public interface AccountRepo extends JpaRepository<AccountEntity, UUID> {

	/**
	 * Atomic guarded debit in ONE roundtrip — the {@code balance >= :amount}
	 * predicate is evaluated by the database (concurrent withdrawals cannot
	 * overdraw) and {@code RETURNING} hands back the new balance, eliminating
	 * the re-read a JPQL UPDATE would force. Empty result ⇒ guard failed
	 * (insufficient funds) or unknown account — the caller disambiguates.
	 */
	@Query(value = """
			UPDATE accounts
			   SET balance = balance - :amount,
			       version = version + 1,
			       updated_at = now()
			 WHERE id = :accountId
			   AND balance >= :amount
			RETURNING balance
			""", nativeQuery = true)
	Optional<BigDecimal> debit(@Param("accountId") UUID accountId, @Param("amount") BigDecimal amount);

	@Query(value = """
			UPDATE accounts
			   SET balance = balance + :amount,
			       version = version + 1,
			       updated_at = now()
			 WHERE id = :accountId
			RETURNING balance
			""", nativeQuery = true)
	Optional<BigDecimal> credit(@Param("accountId") UUID accountId, @Param("amount") BigDecimal amount);
}
