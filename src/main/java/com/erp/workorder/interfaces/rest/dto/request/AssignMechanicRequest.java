package com.erp.workorder.interfaces.rest.dto.request;

import java.util.UUID;

public record AssignMechanicRequest(
        UUID mechanicId
) {}
