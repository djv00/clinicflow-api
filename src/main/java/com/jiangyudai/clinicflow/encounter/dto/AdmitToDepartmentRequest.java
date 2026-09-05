package com.jiangyudai.clinicflow.encounter.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdmitToDepartmentRequest(

        @NotNull(message = "Department ID is required")
        UUID departmentId,

        @NotNull(message = "Ward ID is required")
        UUID wardId,

        UUID bedId,

        @NotNull(message = "Department admission time is required")
        @PastOrPresent(
                message = "Department admission time cannot be in the future"
        )
        OffsetDateTime startedAt
) {

}