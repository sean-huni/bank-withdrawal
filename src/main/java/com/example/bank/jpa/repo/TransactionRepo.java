package com.example.bank.jpa.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.bank.jpa.model.TransactionEntity;

public interface TransactionRepo extends JpaRepository<TransactionEntity, UUID> {

	/** One statement page for the account (backed by the account_id+created_at index). */
	Page<TransactionEntity> findByAccountId(UUID accountId, Pageable pageable);

	/** Account-scoped lookup — an id belonging to another account is treated as not found. */
	Optional<TransactionEntity> findByAccountIdAndId(UUID accountId, UUID id);
}
