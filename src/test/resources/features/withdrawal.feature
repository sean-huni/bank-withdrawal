Feature: Account withdrawal
  The original business capability, preserved: debit an account if funds are
  sufficient, record the movement in the ledger, and emit a withdrawal event.
  Withdrawals are idempotent via the mandatory Idempotency-Key header.

  Scenario: Successful withdrawal reduces the balance and records a ledger entry
    Given an account for "alice" with balance 1000.00
    When "alice" withdraws 250.00
    Then the operation is created with balance after 750.00
    And the response carries a trace id
    And the account balance of "alice" is 750.00
    And the statement of "alice" shows 1 transaction of type "DEBIT"

  Scenario: Withdrawal is rejected when funds are insufficient
    Given an account for "bob" with balance 100.00
    When "bob" withdraws 250.00
    Then the operation fails with status 422 and error code "INSUFFICIENT_FUNDS"
    And the account balance of "bob" is 100.00
    And the statement of "bob" shows 0 transactions

  Scenario: Withdrawal from an unknown account is rejected
    When an unknown account withdraws 50.00
    Then the operation fails with status 404 and error code "ACCOUNT_NOT_FOUND"

  Scenario: Withdrawal with a non-positive amount is rejected with field violations
    Given an account for "carol" with balance 100.00
    When "carol" withdraws -5.00
    Then the operation fails with status 400 and error code "VALIDATION_FAILED"
    And the error reports a violation on field "amount"
    And the account balance of "carol" is 100.00

  Scenario: Replaying the same Idempotency-Key debits the account only once
    Given an account for "dave" with balance 500.00
    When "dave" withdraws 200.00
    And "dave" retries the same withdrawal with the same Idempotency-Key
    Then both responses are created with the same transaction id
    And the account balance of "dave" is 300.00
    And the statement of "dave" shows 1 transaction of type "DEBIT"

  Scenario: Reusing an Idempotency-Key with a different amount is a conflict
    Given an account for "erin" with balance 500.00
    When "erin" withdraws 100.00
    And "erin" withdraws 50.00 reusing the previous Idempotency-Key
    Then the operation fails with status 409 and error code "IDEMPOTENCY_CONFLICT"
    And the account balance of "erin" is 400.00

  Scenario: A withdrawal without an Idempotency-Key is rejected
    Given an account for "frank" with balance 100.00
    When "frank" withdraws 50.00 without an Idempotency-Key
    Then the operation fails with status 400 and error code "MISSING_HEADER"
    And the account balance of "frank" is 100.00

  Scenario: Concurrent withdrawals cannot overdraw the account
    Given an account for "grace" with balance 100.00
    When "grace" withdraws 70.00 twice in parallel
    Then exactly one parallel withdrawal succeeds
    And the account balance of "grace" is 30.00
