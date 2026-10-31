package com.erp.workorder.interfaces.rest.dto.request;

import java.util.List;
import java.util.UUID;

public record UpdateTaskRequest(
        UUID serviceId,
        UUID assignedMechanicId,
        String description,
        List<String> evidenceImages
) {}
