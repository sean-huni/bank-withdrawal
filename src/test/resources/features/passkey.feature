Feature: Passkey-enabled ATM (Spring Security 7 native WebAuthn)
  Card + PIN bootstraps an authenticated kiosk session, which makes the passkey REGISTRATION
  ceremony reachable. The username-less AUTHENTICATION ceremony is public (a returning customer
  has no session yet). Full register/login with a real authenticator is not e2e-testable without
  a virtual authenticator — these scenarios verify the wiring, status codes and challenge shapes.

  Scenario: Card + PIN bootstraps a session that can reach passkey enrolment
    Given a passkey-test account for "nova" with balance 600.00 and card "4000222200002222" pin "1234"
    When an ATM session is bootstrapped with card "4000222200002222" and pin "1234"
    Then the ATM session is established for that account with passkey not yet enrolled
    And passkey registration options can be requested and return a challenge

  Scenario: Wrong PIN at ATM bootstrap is rejected with 401
    Given a passkey-test account for "vera" with balance 400.00 and card "4000222200003333" pin "1234"
    When an ATM session is bootstrapped with card "4000222200003333" and pin "9999"
    Then the ATM bootstrap fails with status 401 and error code "PIN_INVALID"

  Scenario: Unknown card at ATM bootstrap is a 404
    When an ATM session is bootstrapped with card "4000000000009999" and pin "1234"
    Then the ATM bootstrap fails with status 404 and error code "CARD_NOT_FOUND"

  Scenario: Passkey registration options without a session is rejected
    When passkey registration options are requested without a session
    Then the registration ceremony is refused without an authenticated session

  Scenario: The username-less authentication ceremony is public and returns a challenge
    When passkey authentication options are requested without a session
    Then a well-formed passkey challenge is returned

  Scenario: Bootstrap rotates the session id (session-fixation defence, OWASP A07)
    Given a passkey-test account for "iris" with balance 700.00 and card "4000222200004444" pin "1234"
    And a pre-auth ceremony call has established a kiosk session
    When an ATM session is bootstrapped with card "4000222200004444" and pin "1234"
    Then the ATM session is established for that account with passkey not yet enrolled
    And the kiosk session id is rotated by the bootstrap

  Scenario: Bootstrap primes the CSRF token so registration succeeds on the first try
    Given a passkey-test account for "milo" with balance 800.00 and card "4000222200005555" pin "1234"
    When an ATM session is bootstrapped with card "4000222200005555" and pin "1234"
    Then the bootstrap response carries an XSRF-TOKEN cookie
    And passkey registration options succeed on the first try with the primed CSRF token

  Scenario: Ending the kiosk session tears down the authenticated session
    Given a passkey-test account for "otto" with balance 900.00 and card "4000222200006666" pin "1234"
    When an ATM session is bootstrapped with card "4000222200006666" and pin "1234"
    Then the ATM session is established for that account with passkey not yet enrolled
    When the ATM session is ended
    Then the session-end returns 204
    And passkey registration options are refused again after the session ended

  Scenario: Ending the kiosk session with no session is idempotent (204, no error leak)
    When the ATM session is ended with no session
    Then the session-end returns 204

  Scenario: Whoami returns the authenticated session snapshot
    Given an account for "Alice" with balance 1000.00 and card "4000222200007777" pin "1234"
    When an ATM session is bootstrapped with card "4000222200007777" and pin "1234"
    And the current session snapshot is requested
    Then the session snapshot shows holder "Alice" with balance 1000.00 and passkey not enrolled

  Scenario: Whoami is refused without an authenticated session
    When the current session snapshot is requested without a session
    Then the snapshot request is refused with status 401
