package com.jiangyudai.clinicflow.encounter.entity;

import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncounterTest {

    @Test
    void movesAdmittedEncounterIntoDepartment() {
        Encounter encounter = createEncounter();

        encounter.admitToDepartment();

        assertThat(encounter.getStatus())
                .isEqualTo(EncounterStatus.IN_DEPARTMENT);
    }

    @Test
    void rejectsDepartmentAdmissionWhenEncounterIsNotAdmitted() {
        Encounter encounter = createEncounter();
        encounter.admitToDepartment();

        assertThatThrownBy(encounter::admitToDepartment)
                .isInstanceOf(InvalidEncounterStatusException.class)
                .hasMessageContaining("IN_DEPARTMENT");
    }

    private Encounter createEncounter() {
        Patient patient = new Patient(
                "MRN-300001",
                "Maya",
                "Chen",
                LocalDate.of(1990, 5, 14)
        );

        return new Encounter(
                "ENC-2026-000002",
                patient,
                OffsetDateTime.parse("2026-09-01T14:00:00-04:00")
        );
    }
}