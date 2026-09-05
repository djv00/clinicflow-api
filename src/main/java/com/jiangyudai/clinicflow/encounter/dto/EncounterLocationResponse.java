package com.jiangyudai.clinicflow.encounter.dto;

import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EncounterLocationResponse(
        UUID id,
        UUID encounterId,
        UUID departmentId,
        UUID wardId,
        UUID bedId,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt
) {

    public static EncounterLocationResponse from(
            EncounterLocation location
    ) {
        return new EncounterLocationResponse(
                location.getId(),
                location.getEncounter().getId(),
                location.getDepartment().getId(),
                location.getWard().getId(),
                location.getBed() == null
                        ? null
                        : location.getBed().getId(),
                location.getStartedAt(),
                location.getEndedAt()
        );
    }
}