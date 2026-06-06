package com.example.bank.jpa.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.bank.jpa.model.IdempotencyRecordEntity;

public interface IdempotencyRecordRepo extends JpaRepository<IdempotencyRecordEntity, UUID> {

	/** Fresh read used by the aspect's replay path after a duplicate-key rejection. */
	Optional<IdempotencyRecordEntity> findByKey(UUID key);
}
