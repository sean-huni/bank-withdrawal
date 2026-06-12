Feature: Card greeting and PIN authentication
  A 16-digit card resolves to a greeting (no balance). The 4-digit PIN is BCrypt-verified
  server-side; only then is the balance returned. The PIN is never echoed.

  Scenario: A known card returns the greeting without a balance
    Given an account for "zara" with balance 500.00 and card "4000123412341234" pin "1234"
    When the card "4000123412341234" is looked up
    Then the greeting shows holder "zara" and masked card "•••• •••• •••• 1234" with no balance

  Scenario: An unknown card lookup is a 404
    When the card "4000000000000002" is looked up
    Then the operation fails with status 404 and error code "CARD_NOT_FOUND"

  Scenario: A malformed card is a 400 validation error
    When the card "12ab" is looked up
    Then the operation fails with status 400 and error code "VALIDATION_FAILED"
    And the error reports a violation on field "cardNumber"

  Scenario: Correct PIN authenticates and reveals the balance
    Given an account for "yara" with balance 750.00 and card "4000123412341111" pin "1234"
    When the card "4000123412341111" is authenticated with pin "1234"
    Then the authenticated snapshot shows balance 750.00 for holder "yara"

  Scenario: Wrong PIN is rejected with 401
    Given an account for "xena" with balance 300.00 and card "4000123412342222" pin "1234"
    When the card "4000123412342222" is authenticated with pin "9999"
    Then the operation fails with status 401 and error code "PIN_INVALID"

  Scenario: Wrong PIN message resolves in Shona
    Given the client speaks "sn"
    And an account for "rudo" with balance 100.00 and card "4000123412343333" pin "1234"
    When the card "4000123412343333" is authenticated with pin "0000"
    Then the operation fails with status 401 and error code "PIN_INVALID"
    And the error message is "PIN isina kururama"
