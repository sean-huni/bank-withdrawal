Feature: Card lookup (balance inquiry)
  A 16-digit card number resolves to its account snapshot — the ATM "insert card"
  step. Unknown or malformed cards are rejected; the PAN is never echoed back.

  Scenario: A known card returns the account snapshot with a masked number
    Given an account for "zara" with balance 500.00 and card "4000123412341234"
    When the card "4000123412341234" is looked up
    Then the card lookup succeeds with holder "zara" balance 500.00 and masked card "•••• •••• •••• 1234"

  Scenario: An unknown but well-formed card is a 404
    When the card "4000999988887777" is looked up
    Then the operation fails with status 404 and error code "CARD_NOT_FOUND"

  Scenario: A malformed card number is a 400 validation error
    When the card "12ab" is looked up
    Then the operation fails with status 400 and error code "VALIDATION_FAILED"
    And the error reports a violation on field "cardNumber"

  Scenario: The card-not-found message resolves in Shona
    Given the client speaks "sn"
    When the card "4000999988887777" is looked up
    Then the operation fails with status 404 and error code "CARD_NOT_FOUND"
    And the error message is "Kadhi harizivikanwi"
