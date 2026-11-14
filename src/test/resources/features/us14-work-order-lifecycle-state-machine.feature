@US14 @EP06 @erp-work-order-service
# User Story: US14 - Manage Work Order (WO) lifecycle and state machine
Feature: Work Order Lifecycle and State Machine
  As a service advisor or workshop administrator
  I want to open work orders, add service items, quote, approve, reject, complete, and cancel orders
  So that end-to-end vehicle servicing follows controlled state transitions and domain outbox events

  Background:
    Given the "erp-work-order-service" REST API is available at "/api/v1"

  @US14 @happy-path
  # User Story: US14 - Open, quote, and approve a Work Order
  Scenario: Service advisor opens a work order, generates a quote, and approves execution
    Given an authenticated user with role "SERVICE_ADVISOR" in tenant "tenant-lima-01"
    And valid customer "customer-uuid-01", vehicle "vehicle-uuid-01", and branch "branch-uuid-01"
    When the client sends a "POST" request to "/api/v1/work-orders" with JSON payload:
      """
      {
        "branchId": "branch-uuid-01",
        "customerId": "customer-uuid-01",
        "vehicleId": "vehicle-uuid-01",
        "intakeMileage": 45200,
        "intakeReason": "Brake vibration on highway"
      }
      """
    Then the response status code should be 201
    And the JSON response should contain a generated "id"
    And the JSON response field "status" should equal "OPEN"
    When the client sends a "POST" request to "/api/v1/work-orders/{orderId}/quote"
    Then the response status code should be 200
    And the JSON response field "status" should equal "QUOTED"
    When the client sends a "POST" request to "/api/v1/work-orders/{orderId}/approve"
    Then the response status code should be 200
    And the JSON response field "status" should equal "APPROVED"

  @US14 @negative
  # User Story: US14 - Reject invalid state transition on a completed work order
  Scenario: Attempting to approve an already completed work order returns HTTP 409 Conflict
    Given an authenticated user with role "SERVICE_ADVISOR"
    And a work order "wo-completed-01" in state "COMPLETED"
    When the client sends a "POST" request to "/api/v1/work-orders/wo-completed-01/approve"
    Then the response status code should be 409
    And the JSON response should contain an error message indicating invalid state transition
