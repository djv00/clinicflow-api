package com.jiangyudai.clinicflow.encounter.entity;

import com.jiangyudai.clinicflow.encounter.exception.InvalidDischargeTimeException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncounterDischargeTest {

    private static final OffsetDateTime ADMITTED_AT =
            OffsetDateTime.parse("2025-09-01T14:00:00-04:00");

    @Test
    void recordsDischargeAndRejectsASecondDischarge() {
        Encounter encounter = createEncounter();
        encounter.admitToDepartment();
        OffsetDateTime dischargedAt = ADMITTED_AT.plusDays(2);

        encounter.dischargeAt(dischargedAt);

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.DISCHARGED);
        assertThat(encounter.getDischargedAt()).isEqualTo(dischargedAt);
        assertThatThrownBy(() -> encounter.dischargeAt(dischargedAt.plusHours(1)))
                .isInstanceOf(InvalidEncounterStatusException.class);
        assertThat(encounter.getDischargedAt()).isEqualTo(dischargedAt);
    }

    @Test
    void rejectsDischargeBeforeDepartmentAdmission() {
        Encounter encounter = createEncounter();

        assertThatThrownBy(() -> encounter.dischargeAt(ADMITTED_AT.plusDays(1)))
                .isInstanceOf(InvalidEncounterStatusException.class);

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.ADMITTED);
        assertThat(encounter.getDischargedAt()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidDischargeTimes")
    void rejectsInvalidTimeWithoutChangingState(OffsetDateTime dischargedAt) {
        Encounter encounter = createEncounter();
        encounter.admitToDepartment();

        assertThatThrownBy(() -> encounter.dischargeAt(dischargedAt))
                .isInstanceOf(InvalidDischargeTimeException.class);

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
        assertThat(encounter.getDischargedAt()).isNull();
    }

    @Test
    void allowsDischargeAtAdmissionInstantWithAnotherOffset() {
        Encounter encounter = createEncounter();
        encounter.admitToDepartment();
        OffsetDateTime dischargedAt = ADMITTED_AT.withOffsetSameInstant(ZoneOffset.UTC);

        encounter.dischargeAt(dischargedAt);

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.DISCHARGED);
        assertThat(encounter.getDischargedAt()).isEqualTo(dischargedAt);
    }

    private static Stream<OffsetDateTime> invalidDischargeTimes() {
        return Stream.of(null, ADMITTED_AT.minusSeconds(1), OffsetDateTime.now().plusDays(1));
    }

    private Encounter createEncounter() {
        Patient patient = new Patient(
                "MRN-600001", "Maya", "Chen", LocalDate.of(1990, 5, 14)
        );
        return new Encounter("ENC-2025-000003", patient, ADMITTED_AT);
    }
}
