package com.erp.workorder.domain.model;

/**
 * Operational craft specialization for workshop technicians.
 * Decoupled from RBAC authorization role (which is always TECHNICIAN).
 */
public enum TechnicianSpecialty {
    MECHANICS,
    ELECTRONICS,
    BODY_PAINT,
    HVAC,
    TIRES_ALIGNMENT
}
