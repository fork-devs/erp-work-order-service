package com.erp.workorder.interfaces.rest.dto.request;

public record UpdateWorkOrderRequest(
        String diagnosticSummary,
        String diagnosis,
        Integer mileageIn
) {
    public String resolveDiagnosis() {
        if (diagnosticSummary != null && !diagnosticSummary.trim().isEmpty()) {
            return diagnosticSummary.trim();
        }
        if (diagnosis != null && !diagnosis.trim().isEmpty()) {
            return diagnosis.trim();
        }
        return null;
    }
}
