Feature: Deposits and account statement
  Deposits credit the account through the same idempotent ledger flow;
  the statement exposes the ledger as a paged, ordered resource.

  Scenario: Successful deposit increases the balance
    Given an account for "henry" with balance 100.00
    When "henry" deposits 50.00
    Then the operation is created with balance after 150.00
    And the account balance of "henry" is 150.00
    And the statement of "henry" shows 1 transaction of type "CREDIT"

  Scenario: A withdrawal and a deposit appear on the statement newest first
    Given an account for "iris" with balance 300.00
    When "iris" withdraws 100.00
    And "iris" deposits 25.00
    Then the statement of "iris" lists 2 transactions newest first

  Scenario: Statement of an unknown account is rejected
    When the statement of an unknown account is requested
    Then the operation fails with status 404 and error code "ACCOUNT_NOT_FOUND"

  Scenario: An unknown transaction id is rejected
    Given an account for "judy" with balance 100.00
    When a transaction lookup for "judy" uses an unknown transaction id
    Then the operation fails with status 404 and error code "TRANSACTION_NOT_FOUND"

  Scenario: A request with an unsupported API version is rejected
    Given an account for "liam" with balance 100.00
    When "liam" withdraws 10.00 using API version "v9"
    Then the operation fails with status 400 and error code "UNSUPPORTED_API_VERSION"
    And the account balance of "liam" is 100.00

  Scenario: Repeated reads of the same transaction are served from the cache
    Given an account for "kate" with balance 100.00
    When "kate" withdraws 40.00
    And the created transaction is fetched twice
    Then the second fetch is served from the cache
