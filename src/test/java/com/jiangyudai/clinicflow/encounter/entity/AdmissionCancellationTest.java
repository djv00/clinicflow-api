package com.jiangyudai.clinicflow.encounter.entity;

import com.jiangyudai.clinicflow.encounter.exception.InvalidAdmissionCancellationException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionCancellationTest {

    private static final OffsetDateTime ADMITTED_AT =
            OffsetDateTime.parse("2025-09-01T14:00:00-04:00");
    private static final OffsetDateTime CANCELLED_AT = ADMITTED_AT.plusHours(1);

    @Test
    void recordsCancellationWithoutChangingAdmissionOrDischargeTime() {
        Encounter encounter = createEncounter();

        encounter.cancelAdmission(CANCELLED_AT, "demo-clerk");

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.ADMISSION_CANCELLED);
        assertThat(encounter.getAdmissionCancelledAt()).isEqualTo(CANCELLED_AT);
        assertThat(encounter.getAdmissionCancelledBy()).isEqualTo("demo-clerk");
        assertThat(encounter.getAdmittedAt()).isEqualTo(ADMITTED_AT);
        assertThat(encounter.getDischargedAt()).isNull();
    }

    @Test
    void repeatedCancellationDoesNotOverwriteTheOriginalDetails() {
        Encounter encounter = createEncounter();
        encounter.cancelAdmission(CANCELLED_AT, "first-clerk");

        assertThatThrownBy(() -> encounter.cancelAdmission(CANCELLED_AT.plusMinutes(1), "second-clerk"))
                .isInstanceOf(InvalidEncounterStatusException.class);

        assertThat(encounter.getAdmissionCancelledAt()).isEqualTo(CANCELLED_AT);
        assertThat(encounter.getAdmissionCancelledBy()).isEqualTo("first-clerk");
    }

    @ParameterizedTest
    @EnumSource(value = EncounterStatus.class, names = {"IN_DEPARTMENT", "DISCHARGED"})
    void rejectsCancellationAfterDepartmentCareBegins(EncounterStatus status) {
        Encounter encounter = createEncounter();
        encounter.admitToDepartment();
        if (status == EncounterStatus.DISCHARGED) {
            encounter.dischargeAt(CANCELLED_AT);
        }

        assertThatThrownBy(() -> encounter.cancelAdmission(CANCELLED_AT, "demo-clerk"))
                .isInstanceOf(InvalidEncounterStatusException.class);

        assertThat(encounter.getStatus()).isEqualTo(status);
        assertThat(encounter.getAdmissionCancelledAt()).isNull();
        assertThat(encounter.getAdmissionCancelledBy()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidCancellationTimes")
    void rejectsInvalidTimeWithoutChangingTheEncounter(OffsetDateTime cancelledAt) {
        Encounter encounter = createEncounter();

        assertThatThrownBy(() -> encounter.cancelAdmission(cancelledAt, "demo-clerk"))
                .isInstanceOf(InvalidAdmissionCancellationException.class);

        assertNotCancelled(encounter);
    }

    @ParameterizedTest
    @MethodSource("invalidOperators")
    void rejectsInvalidOperatorWithoutChangingTheEncounter(String cancelledBy) {
        Encounter encounter = createEncounter();

        assertThatThrownBy(() -> encounter.cancelAdmission(CANCELLED_AT, cancelledBy))
                .isInstanceOf(InvalidAdmissionCancellationException.class);

        assertNotCancelled(encounter);
    }

    @Test
    void acceptsAdmissionInstantWithAnotherOffsetAndMaximumOperatorLength() {
        Encounter encounter = createEncounter();
        String operator = "a".repeat(100);
        OffsetDateTime cancelledAt = ADMITTED_AT.withOffsetSameInstant(ZoneOffset.UTC);

        encounter.cancelAdmission(cancelledAt, operator);

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.ADMISSION_CANCELLED);
        assertThat(encounter.getAdmissionCancelledAt()).isEqualTo(cancelledAt);
        assertThat(encounter.getAdmissionCancelledBy()).isEqualTo(operator);
    }

    private static Stream<OffsetDateTime> invalidCancellationTimes() {
        return Stream.of(null, ADMITTED_AT.minusSeconds(1), OffsetDateTime.now().plusDays(1));
    }

    private static Stream<String> invalidOperators() {
        return Stream.of(null, "", " \t ", "a".repeat(101));
    }

    private void assertNotCancelled(Encounter encounter) {
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.ADMITTED);
        assertThat(encounter.getAdmissionCancelledAt()).isNull();
        assertThat(encounter.getAdmissionCancelledBy()).isNull();
    }

    private Encounter createEncounter() {
        Patient patient = new Patient(
                "MRN-700001", "Maya", "Chen", LocalDate.of(1990, 5, 14)
        );
        return new Encounter("ENC-2025-000005", patient, ADMITTED_AT);
    }
}
