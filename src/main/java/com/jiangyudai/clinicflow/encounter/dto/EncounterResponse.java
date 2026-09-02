package com.jiangyudai.clinicflow.encounter.dto;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EncounterResponse(
        UUID id,
        String encounterNumber,
        UUID patientId,
        EncounterStatus status,
        OffsetDateTime admittedAt,
        OffsetDateTime dischargedAt
) {

    public static EncounterResponse from(Encounter encounter) {
        return new EncounterResponse(
                encounter.getId(),
                encounter.getEncounterNumber(),
                encounter.getPatient().getId(),
                encounter.getStatus(),
                encounter.getAdmittedAt(),
                encounter.getDischargedAt()
        );
    }
}
