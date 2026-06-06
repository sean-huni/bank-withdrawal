package com.example.bank.jdbc.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

import com.example.bank.jdbc.model.TransactionEntity;

public interface TransactionRepo
		extends CrudRepository<TransactionEntity, UUID>, PagingAndSortingRepository<TransactionEntity, UUID> {

	/** One statement page for the account (backed by the account_id+created_at index). */
	Page<TransactionEntity> findByAccountId(UUID accountId, Pageable pageable);

	/** Account-scoped lookup — an id belonging to another account is treated as not found. */
	Optional<TransactionEntity> findByAccountIdAndId(UUID accountId, UUID id);
}
