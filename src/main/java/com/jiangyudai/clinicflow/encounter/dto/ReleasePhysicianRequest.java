package com.jiangyudai.clinicflow.encounter.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReleasePhysicianRequest(
        @NotNull(message = "Current location ID is required")
        UUID expectedLocationId,

        @NotNull(message = "Release time is required")
        @PastOrPresent(message = "Release time cannot be in the future")
        OffsetDateTime endedAt
) {
}
