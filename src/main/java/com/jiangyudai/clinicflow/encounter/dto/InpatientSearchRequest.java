package com.jiangyudai.clinicflow.encounter.dto;

import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record InpatientSearchRequest(
        @Size(max = 100, message = "Search keyword must not exceed 100 characters")
        String keyword,
        EncounterStatus status,
        UUID departmentId,
        UUID wardId
) {
    @AssertTrue(message = "Inpatient status must be ADMITTED or IN_DEPARTMENT")
    public boolean isStatusValid() {
        return status == null || status == EncounterStatus.ADMITTED || status == EncounterStatus.IN_DEPARTMENT;
    }
}
