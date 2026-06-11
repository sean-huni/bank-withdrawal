Feature: Endpoint security — dual session/JWT auth with scopes and own-account checks

  Scenario: Anonymous withdrawal is refused
    When an anonymous withdrawal of 10.00 is attempted on "SecAlice"
    Then the security response status is 401

  Scenario: A kiosk session may withdraw from its own account
    Given a kiosk session for card "4000222200008888" pin "1234"
    When the session withdraws 10.00 from "SecAlice"
    Then the security response status is 201

  Scenario: A kiosk session may not touch another customer's account
    Given a kiosk session for card "4000222200008888" pin "1234"
    When the session withdraws 10.00 from "SecBob"
    Then the security response status is 403

  Scenario: A valid kiosk session with a garbage bearer header is refused
    Given a kiosk session for card "4000222200008888" pin "1234"
    When the session withdraws 10.00 from "SecAlice" sending a garbage bearer token
    Then the security response status is 401

  Scenario: A JWT with atm.write may withdraw
    Given a client-credentials token with scopes "atm.write"
    When the token withdraws 10.00 from "SecAlice"
    Then the security response status is 201

  Scenario: A JWT with only atm.read may not withdraw but may read transactions
    Given a client-credentials token with scopes "atm.read"
    When the token withdraws 10.00 from "SecAlice"
    Then the security response status is 403
    When the token reads the transactions of "SecAlice"
    Then the security response status is 200

  Scenario: Actuator health is public, the rest needs atm.ops
    When actuator "health" is requested anonymously
    Then the security response status is 200
    When actuator "metrics" is requested anonymously
    Then the security response status is 401
    Given a client-credentials token with scopes "atm.ops"
    When actuator "metrics" is requested with the token
    Then the security response status is 200
