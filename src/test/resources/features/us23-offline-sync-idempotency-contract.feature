@US23 @EP09 @erp-work-order-service
# User Story: US23 - Operate offline in service bay and synchronize changes upon network recovery
Feature: Offline-First Bay Synchronization and Idempotency Contract
  As a workshop mechanic operating in areas without connectivity
  I want queued mobile updates to replay safely using idempotency keys once connectivity resumes
  So that no task progress or work order data is lost or duplicated

  Background:
    Given the "erp-work-order-service" REST API is available at "/api/v1"

  @US23 @happy-path
  # User Story: US23 - Replay offline queued task completion idempotently
  Scenario: Replaying an offline-queued task completion with X-Idempotency-Key succeeds without duplication
    Given an authenticated mechanic with an in-progress task "task-offline-01"
    And a unique idempotency key "offline-sync-key-12345"
    When the mobile client sends a "POST" request to "/api/v1/work-order-tasks/task-offline-01/complete" with header "X-Idempotency-Key: offline-sync-key-12345"
    Then the response status code should be 200
    And the JSON response field "status" should equal "COMPLETED"
    When the mobile client retries the exact same request with header "X-Idempotency-Key: offline-sync-key-12345"
    Then the response status code should be 200
    And no duplicate completion event is recorded

  @US23 @negative
  # User Story: US23 - Detect version conflict when stale offline update arrives
  Scenario: Syncing an offline update against an already finalized work order returns HTTP 409 Conflict
    Given a work order "wo-finalized-01" already finalized on the server with version 5
    When the mobile client sends an offline-queued "PUT" request to "/api/v1/work-orders/wo-finalized-01" with stale version 3
    Then the response status code should be 409
    And the JSON response should indicate a synchronization version conflict for manual resolution
