package com.example.bank.jpa.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.bank.jpa.model.TransactionEntity;

public interface TransactionRepo extends JpaRepository<TransactionEntity, UUID> {

	Page<TransactionEntity> findByAccountId(UUID accountId, Pageable pageable);

	Optional<TransactionEntity> findByAccountIdAndId(UUID accountId, UUID id);
}
