package com.jiangyudai.clinicflow.encounter.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AssignPhysicianRequest(
        @NotNull(message = "Physician ID is required")
        UUID physicianId,

        @NotNull(message = "Current location ID is required")
        UUID expectedLocationId,

        UUID expectedAssignmentId,

        @NotNull(message = "Assignment time is required")
        @PastOrPresent(message = "Assignment time cannot be in the future")
        OffsetDateTime startedAt
) {
}
