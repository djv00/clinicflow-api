package com.jiangyudai.clinicflow.encounter.entity;

import com.jiangyudai.clinicflow.encounter.exception.DischargeRecordConflictException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidDischargeCancellationException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DischargeCancellationTest {

    private static final OffsetDateTime DISCHARGED_AT = OffsetDateTime.parse("2025-09-03T14:00:00-04:00");
    private Encounter encounter;
    private EncounterLocation previous;
    private EncounterDischarge discharge;

    @BeforeEach
    void setUp() {
        encounter = createEncounter();
        previous = locationAt(DISCHARGED_AT.minusDays(1));
        encounter.admitToDepartment();
        encounter.dischargeAt(DISCHARGED_AT);
        previous.endAt(DISCHARGED_AT);
        discharge = new EncounterDischarge(encounter, previous);
    }

    @Test
    void retainsDischargeDetailsWhenCareResumes() {
        OffsetDateTime time = DISCHARGED_AT.plusHours(1);
        EncounterLocation restored = locationAt(time);

        discharge.cancelAt(time, "demo-clerk", restored);
        encounter.cancelDischarge();

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
        assertThat(encounter.getDischargedAt()).isNull();
        assertThat(discharge.getDischargedAt()).isEqualTo(DISCHARGED_AT);
        assertThat(discharge.getLocation()).isSameAs(previous);
        assertThat(previous.getEndedAt()).isEqualTo(DISCHARGED_AT);
        assertThat(discharge.getCancelledAt()).isEqualTo(time);
        assertThat(discharge.getCancelledBy()).isEqualTo("demo-clerk");
        assertThat(discharge.getRestoredLocation()).isSameAs(restored);

        assertThatThrownBy(() -> discharge.cancelAt(time.plusMinutes(1), "another-clerk", restored))
                .isInstanceOf(DischargeRecordConflictException.class);
        assertThat(discharge.getCancelledBy()).isEqualTo("demo-clerk");
    }

    @ParameterizedTest
    @MethodSource("invalidCancellationDetails")
    void rejectsInvalidDetailsWithoutChangingTheDischarge(OffsetDateTime time, String operator) {
        assertThatThrownBy(() -> discharge.cancelAt(time, operator, locationAt(time)))
                .isInstanceOf(InvalidDischargeCancellationException.class);
        assertThat(discharge.getCancelledAt()).isNull();
        assertThat(discharge.getCancelledBy()).isNull();
        assertThat(discharge.getRestoredLocation()).isNull();
    }

    @Test
    void acceptsDischargeInstantWithAnotherOffset() {
        OffsetDateTime time = DISCHARGED_AT.withOffsetSameInstant(ZoneOffset.UTC);
        discharge.cancelAt(time, "a".repeat(100), locationAt(time));
        assertThat(discharge.getCancelledAt()).isEqualTo(time);
    }

    @ParameterizedTest
    @EnumSource(value = EncounterStatus.class, names = {"ADMITTED", "IN_DEPARTMENT", "ADMISSION_CANCELLED"})
    void rejectsReopeningAnEncounterThatIsNotDischarged(EncounterStatus state) {
        Encounter other = createEncounter();
        if (state == EncounterStatus.IN_DEPARTMENT) {
            other.admitToDepartment();
        } else if (state == EncounterStatus.ADMISSION_CANCELLED) {
            other.cancelAdmission(DISCHARGED_AT, "demo-clerk");
        }
        assertThatThrownBy(other::cancelDischarge).isInstanceOf(InvalidEncounterStatusException.class);
        assertThat(other.getStatus()).isEqualTo(state);
    }

    @Test
    void rejectsARecordedDischargeFromAnotherEncounter() {
        Encounter other = createEncounter();
        EncounterLocation foreignLocation = new EncounterLocation(
                other, previous.getDepartment(), previous.getWard(), null, DISCHARGED_AT.minusHours(1)
        );
        foreignLocation.endAt(DISCHARGED_AT);
        assertThatThrownBy(() -> new EncounterDischarge(encounter, foreignLocation))
                .isInstanceOf(DischargeRecordConflictException.class);
    }

    @Test
    void rejectsAResumptionLocationWithADifferentStartTime() {
        assertThatThrownBy(() -> discharge.cancelAt(DISCHARGED_AT, "demo-clerk", locationAt(DISCHARGED_AT.plusHours(1))))
                .isInstanceOf(DischargeRecordConflictException.class);
        assertThat(discharge.getCancelledAt()).isNull();
    }

    private static Stream<Arguments> invalidCancellationDetails() {
        return Stream.of(
                Arguments.of(null, "demo-clerk"),
                Arguments.of(DISCHARGED_AT.minusSeconds(1), "demo-clerk"),
                Arguments.of(OffsetDateTime.now().plusDays(1), "demo-clerk"),
                Arguments.of(DISCHARGED_AT, null),
                Arguments.of(DISCHARGED_AT, ""),
                Arguments.of(DISCHARGED_AT, "  "),
                Arguments.of(DISCHARGED_AT, "a".repeat(101))
        );
    }

    private EncounterLocation locationAt(OffsetDateTime time) {
        return new EncounterLocation(encounter, new Department("CARD", "Cardiology"),
                new Ward("CARD-WARD", "Cardiology Ward"), null, time);
    }

    private Encounter createEncounter() {
        return new Encounter("ENC-2025-000007",
                new Patient("MRN-800001", "Maya", "Chen", LocalDate.of(1990, 5, 14)),
                DISCHARGED_AT.minusDays(2));
    }
}
