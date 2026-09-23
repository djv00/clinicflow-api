package com.jiangyudai.clinicflow.encounter.dto;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterPhysicianAssignment;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;

import java.util.List;
import java.util.UUID;

/** Location and responsibility history read under the same encounter lock. */
public record EncounterPhysicianAssignmentsResponse(
        UUID encounterId,
        EncounterStatus status,
        EncounterLocationResponse currentLocation,
        UUID currentAssignmentId,
        List<PhysicianAssignmentResponse> assignments
) {
    public static EncounterPhysicianAssignmentsResponse from(Encounter encounter, EncounterLocation currentLocation,
                                                             List<EncounterPhysicianAssignment> history) {
        UUID currentId = history.stream().filter(assignment -> assignment.getEndedAt() == null)
                .map(EncounterPhysicianAssignment::getId).findFirst().orElse(null);
        return new EncounterPhysicianAssignmentsResponse(encounter.getId(), encounter.getStatus(),
                currentLocation == null ? null : EncounterLocationResponse.from(currentLocation), currentId,
                history.stream().map(PhysicianAssignmentResponse::from).toList());
    }
}
