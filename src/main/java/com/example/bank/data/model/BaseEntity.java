package com.example.bank.data.model;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;

import lombok.Getter;

/**
 * Common base for Spring Data JDBC aggregates: UUID primary key,
 * optimistic-locking version and audit timestamps (matches the
 * Liquibase-managed columns). The UUID is assigned client-side by
 * {@code JdbcAuditingConfig}'s {@code BeforeConvertCallback} because Data JDBC
 * includes the id in INSERTs, bypassing the DB default.
 */
@Getter
public abstract class BaseEntity {

	@Id
	private UUID id;

	@Version
	private Long version;

	@CreatedDate
	@Column("created_at")
	private Instant createdAt;

	@LastModifiedDate
	@Column("updated_at")
	private Instant updatedAt;

	public void assignIdIfMissing() {
		if (this.id == null) {
			this.id = UUID.randomUUID();
		}
	}
}
