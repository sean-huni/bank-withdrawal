--liquibase formatted sql

--changeset sean:003-create-idempotency-records-table
CREATE TABLE idempotency_records
(
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key     UUID         NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    response_body       TEXT,
    response_type       VARCHAR(256),
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key)
);
--rollback DROP TABLE idempotency_records;
