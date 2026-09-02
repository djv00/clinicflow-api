package com.jiangyudai.clinicflow.encounter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdmitPatientRequest(

        @NotNull(message = "Patient ID is required")
        UUID patientId,

        @NotBlank(message = "Encounter number is required")
        @Size(max = 50, message = "Encounter number is too long")
        String encounterNumber,

        @NotNull(message = "Admission time is required")
        @PastOrPresent(message = "Admission time cannot be in the future")
        OffsetDateTime admittedAt
) {
}
