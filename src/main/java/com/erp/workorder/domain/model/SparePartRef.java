package com.erp.workorder.domain.model;

import java.util.UUID;

/**
 * Logical reference to the part (inventory). The inventory guarantees its
 * existence at the reservation, not this model.
 */
public record SparePartRef(UUID id) {
    public SparePartRef {
        if (id == null) {
            throw new IllegalArgumentException("a spare part reference carries its identifier");
        }
    }
}
