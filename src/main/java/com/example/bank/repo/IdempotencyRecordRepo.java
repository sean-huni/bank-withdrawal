package com.example.bank.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

import com.example.bank.model.IdempotencyRecordEntity;

public interface IdempotencyRecordRepo extends CrudRepository<IdempotencyRecordEntity, UUID> {

	/** Fresh read used by the aspect's replay path after a duplicate-key rejection. */
	Optional<IdempotencyRecordEntity> findByKey(UUID key);
}
