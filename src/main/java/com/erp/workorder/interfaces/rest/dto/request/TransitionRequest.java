package com.erp.workorder.interfaces.rest.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * The request moving an order: the DESTINATION status is explicit, so the
 * machine arbitrates it against the declared transitions.
 */
public record TransitionRequest(
        @NotBlank String destination
) {
}
