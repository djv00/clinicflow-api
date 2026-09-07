package com.jiangyudai.clinicflow.encounter.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.OffsetDateTime;

public record DischargeEncounterRequest(

        @NotNull(message = "Discharge time is required")
        @PastOrPresent(message = "Discharge time cannot be in the future")
        OffsetDateTime dischargedAt
) {
}
