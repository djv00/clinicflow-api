package com.jiangyudai.clinicflow.encounter.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Input for moving an encounter to another department, ward, or bed.
 *
 * @author Jiangyu Dai
 */
public record TransferEncounterRequest(

        @NotNull(message = "Department ID is required")
        UUID departmentId,

        @NotNull(message = "Ward ID is required")
        UUID wardId,

        UUID bedId,

        @NotNull(message = "Transfer time is required")
        @PastOrPresent(
                message = "Transfer time cannot be in the future"
        )
        OffsetDateTime transferredAt
) {

}
