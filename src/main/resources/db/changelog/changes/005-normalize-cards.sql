--liquibase formatted sql

--changeset sean:005-create-cards-table
CREATE TABLE cards
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID         NOT NULL UNIQUE REFERENCES accounts (id),
    card_number VARCHAR(16)  NOT NULL UNIQUE,
    pin_hash    VARCHAR(72)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
--rollback DROP TABLE cards;

--changeset sean:005-migrate-card-data
INSERT INTO cards (account_id, card_number, pin_hash)
SELECT id, card_number, '$2a$10$3b0uTtoVg.p3DDmDss8Xu.uRfWWNLCt5TGOrNDtWstPLnoVAIYlDq' FROM accounts;
--rollback DELETE FROM cards;

--changeset sean:005-drop-account-card-number
DROP INDEX IF EXISTS uk_accounts_card_number;
ALTER TABLE accounts DROP COLUMN card_number;
--rollback ALTER TABLE accounts ADD COLUMN card_number VARCHAR(16);
