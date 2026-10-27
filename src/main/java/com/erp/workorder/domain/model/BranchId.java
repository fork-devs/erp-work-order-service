package com.erp.workorder.domain.model;

import java.util.UUID;

/**
 * Logical reference to the inventory branch the order's parts are reserved
 * and consumed at. The order does not validate its existence: the guarantee
 * comes from the saga reservation.
 */
public record BranchId(UUID id) {

    public BranchId {
        if (id == null) {
            throw new IllegalArgumentException("the branch is mandatory");
        }
    }
}
