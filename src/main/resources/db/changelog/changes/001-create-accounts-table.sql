--liquibase formatted sql

--changeset sean:001-create-accounts-table
CREATE TABLE accounts
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    holder_name VARCHAR(255)   NOT NULL,
    balance     NUMERIC(19, 4) NOT NULL,
    currency    CHAR(3)        NOT NULL DEFAULT 'EUR',
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_accounts_balance_non_negative CHECK (balance >= 0)
);
--rollback DROP TABLE accounts;

--changeset sean:002-seed-accounts
INSERT INTO accounts (holder_name, balance)
VALUES ('Alice', 1000.0000),
       ('Bob', 250.5000);
--rollback DELETE FROM accounts;
