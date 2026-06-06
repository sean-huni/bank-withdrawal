--liquibase formatted sql

--changeset sean:002-create-transactions-table
CREATE TABLE transactions
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type            VARCHAR(16)    NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    balance_after   NUMERIC(19, 4) NOT NULL,
    idempotency_key UUID           NOT NULL,
    account_id      UUID           NOT NULL,
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_transactions_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_transactions_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT chk_transactions_amount_positive CHECK (amount > 0)
);
--rollback DROP TABLE transactions;

--changeset sean:002-index-transactions-account
CREATE INDEX idx_transactions_account_created ON transactions (account_id, created_at DESC);
--rollback DROP INDEX idx_transactions_account_created;
