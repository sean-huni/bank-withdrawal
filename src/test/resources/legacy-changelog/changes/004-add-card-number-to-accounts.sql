--liquibase formatted sql

--changeset sean:004-add-card-number-to-accounts
ALTER TABLE accounts ADD COLUMN card_number VARCHAR(16);
-- Deterministic, Luhn-valid demo cards so the FE demo survives DB reseeds.
UPDATE accounts SET card_number = '4539148803436467' WHERE holder_name = 'Alice';
UPDATE accounts SET card_number = '6011000990139424' WHERE holder_name = 'Bob';
ALTER TABLE accounts ALTER COLUMN card_number SET NOT NULL;
--rollback ALTER TABLE accounts DROP COLUMN card_number;

--changeset sean:004-unique-card-number
CREATE UNIQUE INDEX uk_accounts_card_number ON accounts (card_number);
--rollback DROP INDEX uk_accounts_card_number;
