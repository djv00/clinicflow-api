package com.jiangyudai.clinicflow.encounter.repository;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterPhysicianAssignment;
import com.jiangyudai.clinicflow.encounter.entity.PhysicianAssignmentEndReason;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.physician.entity.Physician;
import com.jiangyudai.clinicflow.physician.repository.PhysicianRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class EncounterPhysicianAssignmentRepositoryTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2025-09-01T10:00:00-04:00");
    @Autowired
    private EncounterPhysicianAssignmentRepository assignments;
    @Autowired
    private PhysicianRepository physicians;
    @Autowired
    private EntityManager entityManager;
    private Department department;
    private Physician physician;
    private Encounter encounter;

    @BeforeEach
    void setUp() {
        department = new Department("MED", "Medicine");
        entityManager.persist(department);
        physician = new Physician("PHY-001", "Maya", "Chen");
        physician.addDepartment(department);
        entityManager.persist(physician);
        encounter = createEncounter("1");
    }

    @Test
    void retainsHistoryAndFindsOnlyTheCurrentPhysicianAfterHandover() {
        var first = assignments.saveAndFlush(assignment(encounter, physician, START));
        Physician replacement = physicians.save(new Physician("PHY-002", "Alex", "Martin"));
        first.endAt(START.plusHours(1), PhysicianAssignmentEndReason.REASSIGNED, "handover-clerk");
        assignments.flush();
        var next = assignments.saveAndFlush(assignment(encounter, replacement, START.plusHours(1)));
        entityManager.clear();

        var history = assignments.findAllByEncounter_IdOrderByStartedAtAscIdAsc(encounter.getId());
        assertThat(history).extracting(EncounterPhysicianAssignment::getId).containsExactly(first.getId(), next.getId());
        assertThat(history.getFirst().getAssignedBy()).isEqualTo("operator");
        assertThat(history.getFirst().getEndedBy()).isEqualTo("handover-clerk");
        assertThat(history.getFirst().getEndReason()).isEqualTo(PhysicianAssignmentEndReason.REASSIGNED);
        var current = assignments.findByEncounter_IdAndEndedAtIsNull(encounter.getId()).orElseThrow();
        entityManager.clear();
        // Directory details remain available after detaching the fetched result.
        assertThat(current.getPhysician().getPhysicianCode()).isEqualTo("PHY-002");
        assertThat(current.getDepartment().getDepartmentCode()).isEqualTo("MED");
    }

    @Test
    void onePhysicianCanServeMultipleEncountersWithoutMixingTheirHistory() {
        Encounter another = createEncounter("2");
        var first = assignments.save(assignment(encounter, physician, START));
        var second = assignments.saveAndFlush(assignment(another, physician, START));
        entityManager.clear();

        assertThat(assignments.findAllByEncounter_IdOrderByStartedAtAscIdAsc(encounter.getId()))
                .extracting(EncounterPhysicianAssignment::getId).containsExactly(first.getId());
        assertThat(assignments.findByEncounter_IdAndEndedAtIsNull(another.getId()).orElseThrow().getId())
                .isEqualTo(second.getId());
    }

    @Test
    void directoryDeactivationAndAffiliationRemovalDoNotEraseResponsibilityHistory() {
        UUID assignmentId = assignments.saveAndFlush(assignment(encounter, physician, START)).getId();
        physician.removeDepartment(department.getId());
        physician.deactivate();
        entityManager.flush();
        entityManager.clear();

        var stored = assignments.findById(assignmentId).orElseThrow();
        assertThat(stored.getDepartment().getId()).isEqualTo(department.getId());
        assertThat(stored.getPhysician().isActive()).isFalse();
        assertThat(stored.getPhysician().getDepartments()).isEmpty();
        assertThat(stored.getEndedAt()).isNull();
    }

    @Test
    void closingAStaleAssignmentCannotReplaceThePersistedClosureAudit() {
        var stale = assignments.saveAndFlush(assignment(encounter, physician, START));
        entityManager.clear();
        var managed = assignments.findById(stale.getId()).orElseThrow();
        managed.endAt(START.plusHours(1), PhysicianAssignmentEndReason.DISCHARGE, "first-clerk");
        assignments.flush();
        assertThat(managed.getVersion()).isGreaterThan(stale.getVersion());
        entityManager.clear();

        stale.endAt(START.plusHours(2), PhysicianAssignmentEndReason.RELEASED, "stale-clerk");
        assertThatThrownBy(() -> assignments.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void deletingAnAssignmentDoesNotCascadeToSharedReferences() {
        var assignment = assignments.saveAndFlush(assignment(encounter, physician, START));
        assignments.delete(assignment);
        assignments.flush();
        entityManager.clear();

        assertThat(entityManager.find(Encounter.class, encounter.getId())).isNotNull();
        assertThat(entityManager.find(Physician.class, physician.getId())).isNotNull();
        assertThat(entityManager.find(Department.class, department.getId())).isNotNull();
    }

    @Test
    void referencedPhysicianCannotBeDeletedAlongWithTheirHistory() {
        assignments.saveAndFlush(assignment(encounter, physician, START));
        entityManager.clear();

        assertThatThrownBy(() -> {
            physicians.deleteById(physician.getId());
            physicians.flush();
        })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Encounter createEncounter(String suffix) {
        Patient patient = new Patient("MRN-" + suffix, "Test", "Patient", LocalDate.of(1990, 1, 1));
        entityManager.persist(patient);
        Encounter result = new Encounter("ENC-" + suffix, patient, START.minusHours(1));
        result.admitToDepartment();
        entityManager.persist(result);
        return result;
    }

    private EncounterPhysicianAssignment assignment(Encounter encounter, Physician physician, OffsetDateTime start) {
        return new EncounterPhysicianAssignment(encounter, physician, department, start, "operator");
    }
}
