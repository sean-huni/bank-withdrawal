Feature: OpenAPI documentation
  The published spec must document concrete, callable URLs. The {api-version}
  URI template variable is resolved by the framework's path-segment versioning,
  never bound as a request parameter — left unrewritten it would surface in
  Swagger UI as an unsubstitutable placeholder.

  Scenario: Documented paths carry the concrete API version
    When the OpenAPI spec is fetched
    Then every documented path starts with "/api/v1/" and carries no version placeholder

  Scenario: Every documented operation accepts an Accept-Language header
    When the OpenAPI spec is fetched
    Then every documented operation declares an "Accept-Language" header parameter
