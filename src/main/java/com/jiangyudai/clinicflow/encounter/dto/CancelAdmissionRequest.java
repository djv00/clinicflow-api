package com.jiangyudai.clinicflow.encounter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record CancelAdmissionRequest(

        @NotNull(message = "Cancellation time is required")
        @PastOrPresent(message = "Cancellation time cannot be in the future")
        OffsetDateTime cancelledAt,

        @NotBlank(message = "Cancellation operator is required")
        @Size(max = 100, message = "Cancellation operator must not exceed 100 characters")
        String cancelledBy
) {
}
