package com.jiangyudai.clinicflow.encounter.dto;

import com.jiangyudai.clinicflow.encounter.entity.EncounterDischarge;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EncounterDischargeResponse(
        UUID id,
        UUID encounterId,
        UUID locationId,
        OffsetDateTime dischargedAt,
        OffsetDateTime cancelledAt,
        String cancelledBy,
        UUID restoredLocationId
) {
    public static EncounterDischargeResponse from(EncounterDischarge discharge) {
        return new EncounterDischargeResponse(
                discharge.getId(), discharge.getEncounter().getId(), discharge.getLocation().getId(),
                discharge.getDischargedAt(), discharge.getCancelledAt(), discharge.getCancelledBy(),
                discharge.getRestoredLocation() == null ? null : discharge.getRestoredLocation().getId()
        );
    }
}
