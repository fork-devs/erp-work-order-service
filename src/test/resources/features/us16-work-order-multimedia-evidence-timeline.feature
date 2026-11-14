@US16 @EP06 @erp-integration-hub @erp-work-order-service
# User Story: US16 - Capture and query immutable multimedia evidence timeline per work order
Feature: Immutable Multimedia Evidence Timeline
  As a mechanic or service advisor
  I want to upload inspection photos and documents linked to a work order stage
  So that vehicle condition is backed by verifiable chronological evidence

  Background:
    Given the media upload endpoint "/api/v1/media/upload" is available

  @US16 @happy-path
  # User Story: US16 - Upload valid inspection photo linked to work order intake stage
  Scenario: Authenticated mechanic uploads an intake inspection image successfully
    Given an authenticated user with role "TECHNICIAN" in tenant "tenant-lima-01"
    And an active work order "wo-uuid-01"
    When the client sends a multipart "POST" request to "/api/v1/media/upload" with image file "front-bumper.jpg", workOrderId "wo-uuid-01", and stage "RECEPTION"
    Then the response status code should be 201
    And the JSON response should contain non-empty fields "url" and "mediaId"
    And the JSON response field "immutable" should be true

  @US16 @negative
  # User Story: US16 - Reject unsupported file format or missing file upload
  Scenario: Uploading an unsupported executable file returns HTTP 400 Bad Request
    Given an authenticated user with role "TECHNICIAN"
    When the client sends a multipart "POST" request to "/api/v1/media/upload" with file "malware.exe"
    Then the response status code should be 400
    And the JSON response should contain an error message indicating unsupported media type
