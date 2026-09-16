package com.jiangyudai.clinicflow.encounter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonIgnoreProperties("cancelledBy")
public record CancelDischargeRequest(
        @NotNull(message = "Cancellation time is required")
        @PastOrPresent(message = "Cancellation time cannot be in the future")
        OffsetDateTime cancelledAt,

        UUID expectedDischargeId
) {
}
