package com.jiangyudai.clinicflow.encounter.dto;

import com.jiangyudai.clinicflow.encounter.entity.EncounterPhysicianAssignment;
import com.jiangyudai.clinicflow.encounter.entity.PhysicianAssignmentEndReason;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PhysicianAssignmentResponse(
        UUID id,
        UUID encounterId,
        UUID physicianId,
        String physicianCode,
        String physicianFirstName,
        String physicianLastName,
        UUID departmentId,
        String departmentCode,
        String departmentName,
        OffsetDateTime startedAt,
        String assignedBy,
        OffsetDateTime endedAt,
        PhysicianAssignmentEndReason endReason,
        String endedBy
) {
    public static PhysicianAssignmentResponse from(EncounterPhysicianAssignment assignment) {
        var physician = assignment.getPhysician();
        var department = assignment.getDepartment();
        return new PhysicianAssignmentResponse(assignment.getId(), assignment.getEncounter().getId(),
                physician.getId(), physician.getPhysicianCode(), physician.getFirstName(), physician.getLastName(),
                department.getId(), department.getDepartmentCode(), department.getDepartmentName(),
                assignment.getStartedAt(), assignment.getAssignedBy(), assignment.getEndedAt(),
                assignment.getEndReason(), assignment.getEndedBy());
    }
}
