package com.jiangyudai.clinicflow.encounter.dto;

import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A current stay with patient identity and its open placement, if assigned. */
public record InpatientResponse(
        UUID id,
        String encounterNumber,
        EncounterStatus status,
        OffsetDateTime admittedAt,
        UUID patientId,
        String firstName,
        String lastName,
        String medicalRecordNumber,
        LocalDate dateOfBirth,
        UUID departmentId,
        String departmentName,
        String departmentCode,
        UUID wardId,
        String wardName,
        String wardCode,
        UUID bedId,
        String bedNumber
) {
}
