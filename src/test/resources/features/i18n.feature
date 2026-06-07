Feature: Localized error messages
  Error payloads resolve their human-readable message from the locale bundle
  selected by the Accept-Language header; wire codes never change. Unsupported
  locales fall back to the base (English) bundle.

  Scenario: A business error resolves in Shona
    Given the client speaks "sn"
    And an account for "rudo" with balance 100.00
    When "rudo" withdraws 250.00
    Then the operation fails with status 422 and error code "INSUFFICIENT_FUNDS"
    And the error message is "Mari haikwane kubvisa"

  Scenario: A business error resolves in English
    Given the client speaks "en"
    And an account for "simba" with balance 100.00
    When "simba" withdraws 250.00
    Then the operation fails with status 422 and error code "INSUFFICIENT_FUNDS"
    And the error message is "Insufficient funds for withdrawal"

  Scenario: An unsupported locale falls back to English
    Given the client speaks "fr"
    And an account for "tatenda" with balance 100.00
    When "tatenda" withdraws 250.00
    Then the operation fails with status 422 and error code "INSUFFICIENT_FUNDS"
    And the error message is "Insufficient funds for withdrawal"

  Scenario: A message argument is interpolated into the localized text
    Given the client speaks "sn"
    When an unknown account withdraws 50.00
    Then the operation fails with status 404 and error code "ACCOUNT_NOT_FOUND"
    And the error message contains "haina kuwanikwa"

  Scenario: A framework error resolves in Shona with its argument interpolated
    Given the client speaks "sn"
    And an account for "tendai" with balance 100.00
    When "tendai" withdraws 50.00 without an Idempotency-Key
    Then the operation fails with status 400 and error code "MISSING_HEADER"
    And the error message is "Musoro unodiwa 'Idempotency-Key' haupo"
