package com.erp.workorder.domain.model;

import java.util.UUID;

/** Logical reference to the customer (fleet). Its existence is not validated locally. */
public record CustomerRef(UUID id) {
    public CustomerRef {
        if (id == null) {
            throw new IllegalArgumentException("a work order requires a customer reference");
        }
    }
}
