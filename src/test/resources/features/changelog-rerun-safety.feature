Feature: Liquibase changelog re-run safety
  The SQL/YAML changelogs were converted to XML (commit caf3394), which renamed every
  changeset file. Liquibase identity is (id, author, FILENAME), so against a database that
  had run the old .sql changelogs every changeset counted as NEW and re-executed its DDL —
  the app threw `relation "accounts" already exists` on launch. This was caught at dev launch,
  not at test. These scenarios make the manual docker repro an automated regression: each runs
  the REAL db/changelog/db.changelog-master.xml against a database left in a historical state by
  the frozen legacy fixtures, asserting convergence to one final schema with no DDL re-run.

  # Scenario 1: the FULL legacy end-state (old 001-005 applied). This is the exact state that
  # failed at dev launch before the precondition fix.
  Scenario: Applying the changelog to a database created by the legacy SQL changelogs does not re-run DDL
    Given a database left by the legacy SQL changelogs 001 through 005
    When the current Liquibase changelog is applied
    Then the update completes without error
    And changesets "001-create-accounts-table,002-seed-accounts,002-create-transactions-table,002-index-transactions-account,003-create-idempotency-records-table" are marked as MARK_RAN
    And changesets "004-add-card-number-to-accounts,004-unique-card-number,005-create-cards-table,005-migrate-card-data,005-drop-account-card-number" are marked as MARK_RAN
    And changesets "006-create-user-entities,006-create-user-credentials" are marked as EXECUTED
    And the seeded account data is preserved
    And the accounts table has no card_number column
    And the cards table has 2 rows

  # Scenario 2: a half-migrated legacy database (old 001-004 applied, 005 normalization never ran).
  # The 005 chain must run to finish the migration and converge to the same end-state as scenario 1.
  Scenario: Applying the changelog to a half-migrated database completes the migration
    Given a database left by the legacy SQL changelogs 001 through 004
    When the current Liquibase changelog is applied
    Then the update completes without error
    And changesets "005-create-cards-table,005-migrate-card-data,005-drop-account-card-number" are marked as EXECUTED
    And changesets "001-create-accounts-table,004-add-card-number-to-accounts" are marked as MARK_RAN
    And the cards table has 2 rows
    And the accounts table has no card_number column
    And the schema matches the fully-migrated end-state

  # Scenario 3: general re-run safety — applying the changelog twice on a fresh DB is a no-op.
  Scenario: Applying the changelog twice is a no-op
    Given a fresh empty database
    When the current Liquibase changelog is applied
    And the current Liquibase changelog is applied again
    Then the update completes without error
    And the second run executed no changesets
