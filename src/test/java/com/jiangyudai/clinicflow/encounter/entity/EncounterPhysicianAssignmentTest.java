package com.jiangyudai.clinicflow.encounter.entity;

import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.physician.entity.Physician;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncounterPhysicianAssignmentTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2025-09-01T10:00:00-04:00");
    private final Encounter encounter = new Encounter("ENC-001",
            new Patient("MRN-001", "Test", "Patient", LocalDate.of(1990, 1, 1)), START.minusHours(1));
    private final Physician physician = new Physician("PHY-001", "Maya", "Chen");
    private final Department department = new Department("MED", "Medicine");

    @Test
    void retainsStartAuditAndReferencesWhenClosingResponsibility() {
        var assignment = assignment();

        assignment.endAt(START.plusHours(2), PhysicianAssignmentEndReason.REASSIGNED, "Second.Operator");

        assertThat(assignment.getEncounter()).isSameAs(encounter);
        assertThat(assignment.getPhysician()).isSameAs(physician);
        assertThat(assignment.getDepartment()).isSameAs(department);
        assertThat(assignment.getStartedAt()).isEqualTo(START);
        assertThat(assignment.getAssignedBy()).isEqualTo("First.Operator");
        assertThat(assignment.getEndedAt()).isEqualTo(START.plusHours(2));
        assertThat(assignment.getEndReason()).isEqualTo(PhysicianAssignmentEndReason.REASSIGNED);
        assertThat(assignment.getEndedBy()).isEqualTo("Second.Operator");
    }

    @Test
    void permitsAnEqualInstantWithADifferentOffset() {
        var assignment = assignment();
        OffsetDateTime sameInstant = START.withOffsetSameInstant(ZoneOffset.UTC);

        assignment.endAt(sameInstant, PhysicianAssignmentEndReason.RELEASED, "operator");

        assertThat(assignment.getEndedAt().isEqual(assignment.getStartedAt())).isTrue();
    }

    @Test
    void cannotOverwriteAClosedAssignment() {
        var assignment = assignment();
        assignment.endAt(START.plusHours(1), PhysicianAssignmentEndReason.DISCHARGE, "operator");

        assertThatThrownBy(() -> assignment.endAt(START.plusHours(2), PhysicianAssignmentEndReason.REASSIGNED, "other"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(assignment.getEndedAt()).isEqualTo(START.plusHours(1));
        assertThat(assignment.getEndReason()).isEqualTo(PhysicianAssignmentEndReason.DISCHARGE);
        assertThat(assignment.getEndedBy()).isEqualTo("operator");
    }

    @Test
    void rejectsInvalidTimesAndLeavesTheAssignmentOpen() {
        var assignment = assignment();
        for (OffsetDateTime end : new OffsetDateTime[]{null, START.minusSeconds(1), OffsetDateTime.now().plusDays(1)}) {
            assertThatThrownBy(() -> assignment.endAt(end, PhysicianAssignmentEndReason.DISCHARGE, "operator"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertOpen(assignment);
        }
        for (OffsetDateTime start : new OffsetDateTime[]{null, encounter.getAdmittedAt().minusSeconds(1), OffsetDateTime.now().plusDays(1)}) {
            assertThatThrownBy(() -> new EncounterPhysicianAssignment(encounter, physician, department, start, "operator"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingOperatorsWithoutPartiallyClosing(String operator) {
        assertThatThrownBy(() -> new EncounterPhysicianAssignment(encounter, physician, department, START, operator))
                .isInstanceOf(IllegalArgumentException.class);
        var assignment = assignment();

        assertThatThrownBy(() -> assignment.endAt(START.plusHours(1), PhysicianAssignmentEndReason.RELEASED, operator))
                .isInstanceOf(IllegalArgumentException.class);
        assertOpen(assignment);
    }

    @Test
    void rejectsOverlongAuditAndMissingReasonWithoutPartialChanges() {
        String overlong = "x".repeat(101);
        assertThatThrownBy(() -> new EncounterPhysicianAssignment(encounter, physician, department, START, overlong))
                .isInstanceOf(IllegalArgumentException.class);
        var assignment = assignment();
        assertThatThrownBy(() -> assignment.endAt(START, PhysicianAssignmentEndReason.RELEASED, overlong))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assignment.endAt(START, null, "operator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertOpen(assignment);
    }

    @Test
    void requiresAllThreeBusinessReferences() {
        assertThatThrownBy(() -> new EncounterPhysicianAssignment(null, physician, department, START, "operator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EncounterPhysicianAssignment(encounter, null, department, START, "operator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EncounterPhysicianAssignment(encounter, physician, null, START, "operator"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private EncounterPhysicianAssignment assignment() {
        return new EncounterPhysicianAssignment(encounter, physician, department, START, "First.Operator");
    }

    private static void assertOpen(EncounterPhysicianAssignment assignment) {
        assertThat(assignment.getEndedAt()).isNull();
        assertThat(assignment.getEndReason()).isNull();
        assertThat(assignment.getEndedBy()).isNull();
    }
}
