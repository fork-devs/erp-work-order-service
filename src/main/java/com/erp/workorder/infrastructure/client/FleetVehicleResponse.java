package com.erp.workorder.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * The vehicle detail the fleet answers for a GET of one vehicle: only the
 * identifier and the customer it is registered against reach this record.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FleetVehicleResponse(
        UUID id,
        UUID customerId
) {
}
