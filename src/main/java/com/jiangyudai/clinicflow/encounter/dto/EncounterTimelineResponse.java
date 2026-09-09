package com.jiangyudai.clinicflow.encounter.dto;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterDischarge;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;

import java.util.List;

public record EncounterTimelineResponse(
        EncounterResponse encounter,
        List<EncounterLocationResponse> locations,
        List<EncounterDischargeResponse> discharges
) {
    public static EncounterTimelineResponse from(
            Encounter encounter,
            List<EncounterLocation> locations,
            List<EncounterDischarge> discharges
    ) {
        return new EncounterTimelineResponse(
                EncounterResponse.from(encounter),
                locations.stream().map(EncounterLocationResponse::from).toList(),
                discharges.stream().map(EncounterDischargeResponse::from).toList()
        );
    }
}
