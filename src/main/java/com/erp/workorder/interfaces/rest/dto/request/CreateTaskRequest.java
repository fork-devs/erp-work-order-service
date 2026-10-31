package com.erp.workorder.interfaces.rest.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateTaskRequest(
        @NotNull UUID serviceId,
        @NotBlank String description,
        List<String> evidenceImages
) {}
