@US15 @EP06 @erp-work-order-service
# User Story: US15 - Assign mechanics, time tasks with geofence, and link spare parts in bay
Feature: Work Order Tasks, Mechanic Assignment, Geofenced Timing, and Parts Linking
  As a mechanic or service advisor
  I want to attach tasks to a work order, assign mechanics, track execution time with geofence, and link spare parts
  So that real bay productivity and stock reservations are accurately tracked

  Background:
    Given the "erp-work-order-service" REST API is available at "/api/v1"

  @US15 @happy-path
  # User Story: US15 - Create task, assign mechanic, and start execution timer
  Scenario: Service advisor creates a task, assigns a mechanic, and mechanic starts the timer
    Given an authenticated user with role "SERVICE_ADVISOR" and an approved work order "wo-uuid-01"
    When the client sends a "POST" request to "/api/v1/work-orders/wo-uuid-01/tasks" with JSON payload:
      """
      {
        "title": "Replace front brake pads",
        "standardMinutes": 60
      }
      """
    Then the response status code should be 201
    When the client sends a "POST" request to "/api/v1/work-order-tasks/{taskId}/assign-mechanic" with JSON payload:
      """
      {
        "mechanicId": "mech-uuid-01"
      }
      """
    Then the response status code should be 200
    When the mechanic sends a "POST" request to "/api/v1/work-order-tasks/{taskId}/start" with geofence coordinates inside branch radius
    Then the response status code should be 200
    And the JSON response field "status" should equal "IN_PROGRESS"

  @US15 @negative
  # User Story: US15 - Prevent starting a second concurrent timer for the same mechanic
  Scenario: Starting a second active task timer for a busy mechanic returns HTTP 409 Conflict
    Given mechanic "mech-uuid-01" already has an active task in status "IN_PROGRESS"
    And another pending task "task-uuid-02" assigned to "mech-uuid-01"
    When the mechanic sends a "POST" request to "/api/v1/work-order-tasks/task-uuid-02/start"
    Then the response status code should be 409
    And the JSON response should contain an error message indicating mechanic already has an active timer
