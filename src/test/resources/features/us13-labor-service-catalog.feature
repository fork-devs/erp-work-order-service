@US13 @EP06 @erp-work-order-service
# User Story: US13 - Manage labor service catalog and standard times
Feature: Labor Service Catalog and Standard Times
  As a workshop administrator or service advisor
  I want to create, query, update, and remove standardized labor services with base rates and standard hours
  So that quotes and productivity metrics use consistent operational benchmarks

  Background:
    Given the "erp-work-order-service" REST API is available at "/api/v1"

  @US13 @happy-path
  # User Story: US13 - Create and query a standardized labor service
  Scenario: Service advisor registers a new labor service in the catalog
    Given an authenticated user with role "SERVICE_ADVISOR" in tenant "tenant-lima-01"
    When the client sends a "POST" request to "/api/v1/services" with JSON payload:
      """
      {
        "code": "SRV-ALIGN-01",
        "description": "4-Wheel Computerized Alignment",
        "baseRate": 120.00,
        "standardHours": 1.5
      }
      """
    Then the response status code should be 201
    And the JSON response should contain a generated "id"
    And the JSON response field "code" should equal "SRV-ALIGN-01"

  @US13 @negative
  # User Story: US13 - Reject negative labor rate or standard hours
  Scenario: Creating a labor service with a negative base rate returns HTTP 400
    Given an authenticated user with role "SERVICE_ADVISOR"
    When the client sends a "POST" request to "/api/v1/services" with JSON payload:
      """
      {
        "code": "SRV-NEG-01",
        "description": "Invalid Negative Rate Service",
        "baseRate": -50.00,
        "standardHours": 1.0
      }
      """
    Then the response status code should be 400
    And the JSON response should contain a validation error for "baseRate"
