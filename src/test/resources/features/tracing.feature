Feature: Distributed tracing over real HTTP
  The suite runs against the embedded server on a random port, so W3C trace
  context extraction is exercised for real: a client-supplied traceparent must
  surface as the envelope's traceId.

  Scenario: The response envelope carries a trace id
    Given an account for "tina" with balance 100.00
    When "tina" withdraws 10.00
    Then the operation is created with balance after 90.00
    And the response carries a trace id

  Scenario: A client-supplied W3C traceparent is honoured end-to-end
    Given an account for "tom" with balance 100.00
    When "tom" withdraws 10.00 sending a client traceparent
    Then the operation is created with balance after 90.00
    And the envelope trace id equals the client-sent trace id
