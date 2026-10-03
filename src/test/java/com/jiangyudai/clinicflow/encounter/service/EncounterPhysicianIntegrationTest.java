package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.dto.PhysicianAssignmentResponse;
import com.jiangyudai.clinicflow.encounter.service.EncounterQueryService;
import com.jiangyudai.clinicflow.encounter.repository.EncounterLocationRepository;
import com.jiangyudai.clinicflow.encounter.entity.EncounterPhysicianAssignment;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.entity.PhysicianAssignmentEndReason;
import com.jiangyudai.clinicflow.encounter.exception.*;
import com.jiangyudai.clinicflow.encounter.repository.EncounterPhysicianAssignmentRepository;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import com.jiangyudai.clinicflow.physician.exception.PhysicianNotFoundException;
import com.jiangyudai.clinicflow.physician.service.PhysicianService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:encounter-physician-it;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class EncounterPhysicianIntegrationTest {
    @Autowired
    private EncounterQueryService encounterQueries;


    @Autowired
    private EncounterLocationRepository locations;
    private static final OffsetDateTime ENTERED = OffsetDateTime.parse("2025-09-01T10:00:00-04:00");
    @Autowired private EncounterPhysicianService service;
    @Autowired private EncounterService encounters;
    @Autowired private PatientService patients;
    @Autowired private PhysicianService physicians;
    @Autowired private EncounterPhysicianAssignmentRepository assignments;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mvc;
    @PersistenceContext private EntityManager entityManager;
    private TransactionTemplate transactions;
    private UUID departmentId;
    private UUID otherDepartmentId;
    private UUID wardId;
    private UUID bedId;
    private UUID secondBedId;
    private UUID physicianId;
    private UUID replacementId;
    private UUID encounterId;
    private UUID locationId;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            String code = UUID.randomUUID().toString().substring(0, 16);
            Department department = new Department("A-" + code, "Medicine");
            Department other = new Department("B-" + code, "Surgery");
            Ward ward = new Ward("W-" + code, "Ward");
            entityManager.persist(department);
            entityManager.persist(other);
            entityManager.persist(ward);
            Bed bed = new Bed("1", ward);
            Bed second = new Bed("2", ward);
            entityManager.persist(bed);
            entityManager.persist(second);
            departmentId = department.getId();
            otherDepartmentId = other.getId();
            wardId = ward.getId();
            bedId = bed.getId();
            secondBedId = second.getId();
        });
        physicianId = physicians.create("PHY-" + UUID.randomUUID().toString().substring(0, 16),
                "Maya", "Chen", Set.of(departmentId, otherDepartmentId)).id();
        replacementId = physicians.create("PHY-" + UUID.randomUUID().toString().substring(0, 16),
                "Alex", "Martin", Set.of(departmentId)).id();
        encounterId = admit();
        locationId = encounters.admitToDepartment(encounterId, departmentId, wardId, bedId, ENTERED).id();
    }

    @Test
    void handsOverAndReleasesWithoutErasingHistory() {
        var first = assign(physicianId, null, ENTERED);
        var next = assign(replacementId, first.id(), ENTERED.plusHours(1));
        service.release(encounterId, locationId, next.id(), ENTERED.plusHours(2), "Release.Clerk");

        var history = service.getHistory(encounterId);
        assertThat(history).extracting(EncounterPhysicianAssignment::getId).containsExactly(first.id(), next.id());
        assertThat(history.getFirst().getAssignedBy()).isEqualTo("Assignment.Clerk");
        assertThat(history.getFirst().getEndReason()).isEqualTo(PhysicianAssignmentEndReason.REASSIGNED);
        assertThat(history.getFirst().getEndedAt()).isEqualTo(next.startedAt());
        assertThat(history.getLast().getEndReason()).isEqualTo(PhysicianAssignmentEndReason.RELEASED);
        assertThat(history.getLast().getEndedBy()).isEqualTo("Release.Clerk");
        assertThat(assignments.findByEncounter_IdAndEndedAtIsNull(encounterId)).isEmpty();
        assertThat(encounterQueries.getEncounter(encounterId).status()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
        assertThat(service.getHistory(admit())).isEmpty();
    }

    @Test
    void rejectsStaleAssignmentLocationAndRepeatedSaves() {
        var first = assign(physicianId, null, ENTERED);
        assertThatThrownBy(() -> assign(replacementId, null, ENTERED)).isInstanceOf(PhysicianAssignmentConflictException.class);
        assertThatThrownBy(() -> assign(physicianId, first.id(), ENTERED)).isInstanceOf(PhysicianAssignmentConflictException.class);
        var next = assign(replacementId, first.id(), ENTERED.plusHours(1));
        assertThatThrownBy(() -> service.release(encounterId, locationId, first.id(), ENTERED.plusHours(2), "operator"))
                .isInstanceOf(PhysicianAssignmentConflictException.class);
        var moved = encounters.transferEncounter(encounterId, departmentId, wardId, secondBedId, ENTERED.plusHours(2), "operator", currentLocationId(encounterId));
        assertThatThrownBy(() -> service.assign(encounterId, physicianId, locationId, next.id(), ENTERED.plusHours(3), "operator"))
                .isInstanceOf(PhysicianAssignmentConflictException.class);
        assertThat(assignments.findByEncounter_IdAndEndedAtIsNull(encounterId).orElseThrow().getId()).isEqualTo(next.id());
        assertThat(moved.id()).isNotEqualTo(locationId);
    }

    @Test
    void rejectsIneligiblePhysiciansBeforeChangingCurrentResponsibility() {
        var first = assign(physicianId, null, ENTERED);
        var replacement = physicians.get(replacementId);
        physicians.changeActive(replacementId, false, replacement.version());
        assertThatThrownBy(() -> assign(replacementId, first.id(), ENTERED.plusHours(1)))
                .isInstanceOf(InvalidPhysicianAssignmentException.class);
        replacement = physicians.get(replacementId);
        physicians.changeActive(replacementId, true, replacement.version());
        replacement = physicians.get(replacementId);
        physicians.update(replacementId, replacement.firstName(), replacement.lastName(), Set.of(otherDepartmentId), replacement.version());
        assertThatThrownBy(() -> assign(replacementId, first.id(), ENTERED.plusHours(1)))
                .isInstanceOf(InvalidPhysicianAssignmentException.class);
        assertThatThrownBy(() -> assign(UUID.randomUUID(), first.id(), ENTERED.plusHours(1)))
                .isInstanceOf(PhysicianNotFoundException.class);
        assertThat(service.getHistory(encounterId)).singleElement().satisfies(item -> assertThat(item.getEndedAt()).isNull());
    }

    @Test
    void rejectsInactiveDepartmentButStillAllowsResponsibilityToBeReleased() {
        var first = assign(physicianId, null, ENTERED);
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery("UPDATE departments SET active = false WHERE id = :id")
                .setParameter("id", departmentId).executeUpdate());
        assertThatThrownBy(() -> assign(replacementId, first.id(), ENTERED.plusHours(1)))
                .isInstanceOf(InvalidPhysicianAssignmentException.class);
        service.release(encounterId, locationId, first.id(), ENTERED.plusHours(1), "operator");
        assertThat(assignments.findByEncounter_IdAndEndedAtIsNull(encounterId)).isEmpty();
    }

    @Test
    void assignmentRequiresDepartmentCareAndAnExistingEncounter() {
        UUID waiting = admit();
        assertThatThrownBy(() -> service.assign(waiting, physicianId, locationId, null, ENTERED, "operator"))
                .isInstanceOf(InvalidEncounterStatusException.class);
        encounters.cancelAdmission(waiting, ENTERED, "operator");
        assertThatThrownBy(() -> service.assign(waiting, physicianId, locationId, null, ENTERED, "operator"))
                .isInstanceOf(InvalidEncounterStatusException.class);
        encounters.dischargeEncounter(encounterId, ENTERED.plusHours(2), "operator", currentLocationId(encounterId));
        assertThatThrownBy(() -> assign(physicianId, null, ENTERED)).isInstanceOf(InvalidEncounterStatusException.class);
        assertThatThrownBy(() -> service.getHistory(UUID.randomUUID())).isInstanceOf(EncounterNotFoundException.class);
    }

    @Test
    void rejectsInvalidTimesAndAuditWithoutWritingRecords() {
        for (OffsetDateTime time : new OffsetDateTime[]{null, ENTERED.minusSeconds(1), OffsetDateTime.now().plusDays(1)}) {
            assertThatThrownBy(() -> assign(physicianId, null, time)).isInstanceOf(InvalidPhysicianAssignmentException.class);
        }
        for (String operator : new String[]{null, " ", "x".repeat(101)}) {
            assertThatThrownBy(() -> service.assign(encounterId, physicianId, locationId, null, ENTERED, operator))
                    .isInstanceOf(InvalidPhysicianAssignmentException.class);
        }
        assertThatThrownBy(() -> assign(null, null, ENTERED)).isInstanceOf(InvalidPhysicianAssignmentException.class);
        assertThatThrownBy(() -> service.release(encounterId, locationId, null, ENTERED, "operator"))
                .isInstanceOf(PhysicianAssignmentConflictException.class);
        assertThat(service.getHistory(encounterId)).isEmpty();
    }

    @Test
    void rejectsBackdatingAcrossOpenAndClosedResponsibilityEvenWhenUnassigned() {
        var first = assign(physicianId, null, ENTERED.plusHours(1));
        assertThatThrownBy(() -> assign(replacementId, first.id(), ENTERED))
                .isInstanceOf(PhysicianAssignmentConflictException.class);
        assertThatThrownBy(() -> service.release(encounterId, locationId, first.id(), ENTERED, "operator"))
                .isInstanceOf(PhysicianAssignmentConflictException.class);
        var before = encounterQueries.getTimeline(encounterId);
        assertThatThrownBy(() -> encounters.dischargeEncounter(encounterId, ENTERED, "operator", currentLocationId(encounterId)))
                .isInstanceOf(PhysicianAssignmentConflictException.class);
        assertThat(encounterQueries.getTimeline(encounterId)).isEqualTo(before);
        service.release(encounterId, locationId, first.id(), ENTERED.plusHours(2), "operator");
        assertThatThrownBy(() -> assign(replacementId, null, ENTERED.plusHours(1)))
                .isInstanceOf(PhysicianAssignmentConflictException.class);
        assertThatThrownBy(() -> encounters.dischargeEncounter(encounterId, ENTERED.plusHours(1), "operator", currentLocationId(encounterId)))
                .isInstanceOf(PhysicianAssignmentConflictException.class);
        assertThatThrownBy(() -> encounters.transferEncounter(encounterId, otherDepartmentId, wardId, null, ENTERED.plusHours(1), "operator", currentLocationId(encounterId)))
                .isInstanceOf(PhysicianAssignmentConflictException.class);
        assertThat(encounterQueries.getTimeline(encounterId)).isEqualTo(before);
        var next = assign(replacementId, null, ENTERED.plusHours(2));
        assertThat(next.startedAt()).isEqualTo(ENTERED.plusHours(2));
    }

    @Test
    void sameDepartmentMoveRetainsPhysicianButDepartmentTransferEndsResponsibility() {
        var first = assign(physicianId, null, ENTERED);
        encounters.transferEncounter(encounterId, departmentId, wardId, secondBedId, ENTERED.plusHours(1), "bed-clerk", currentLocationId(encounterId));
        assertThat(assignments.findByEncounter_IdAndEndedAtIsNull(encounterId).orElseThrow().getId()).isEqualTo(first.id());
        var destination = encounters.transferEncounter(encounterId, otherDepartmentId, wardId, null, ENTERED.plusHours(2), "transfer-clerk", currentLocationId(encounterId));
        assertThat(assignments.findByEncounter_IdAndEndedAtIsNull(encounterId)).isEmpty();
        var ended = service.getHistory(encounterId).getFirst();
        assertThat(ended.getEndedAt()).isEqualTo(ENTERED.plusHours(2));
        assertThat(ended.getEndReason()).isEqualTo(PhysicianAssignmentEndReason.DEPARTMENT_TRANSFER);
        assertThat(ended.getEndedBy()).isEqualTo("transfer-clerk");
        var selected = service.assign(encounterId, physicianId, destination.id(), null, ENTERED.plusHours(2), "receiving-clerk");
        assertThat(selected.departmentId()).isEqualTo(otherDepartmentId);
        assertThat(selected.id()).isNotEqualTo(first.id());
    }

    @Test
    void cancellingDischargePreservesDoctorHistoryAndRequiresExplicitReselection() {
        var first = assign(physicianId, null, ENTERED);
        encounters.dischargeEncounter(encounterId, ENTERED.plusHours(1), "discharge-clerk", currentLocationId(encounterId));
        var physician = physicians.get(physicianId);
        physicians.changeActive(physicianId, false, physician.version());
        encounters.cancelDischarge(encounterId, ENTERED.plusHours(2), "correction-clerk", currentDischargeId(encounterId));
        var restored = encounterQueries.getTimeline(encounterId).locations().getLast();
        assertThat(assignments.findByEncounter_IdAndEndedAtIsNull(encounterId)).isEmpty();
        var ended = service.getHistory(encounterId).getFirst();
        assertThat(ended.getId()).isEqualTo(first.id());
        assertThat(ended.getEndReason()).isEqualTo(PhysicianAssignmentEndReason.DISCHARGE);
        assertThat(ended.getEndedBy()).isEqualTo("discharge-clerk");
        assertThatThrownBy(() -> service.assign(encounterId, physicianId, restored.id(), null, restored.startedAt(), "operator"))
                .isInstanceOf(InvalidPhysicianAssignmentException.class);
        var confirmed = service.assign(encounterId, replacementId, restored.id(), null, restored.startedAt(), "operator");
        assertThat(confirmed.startedAt()).isEqualTo(ended.getEndedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"handover", "transfer", "discharge"})
    void rollsBackAllChangesAfterAFlushedWorkflowFails(String workflow) {
        var first = assign(physicianId, null, ENTERED);
        long originalVersion = assignments.findById(first.id()).orElseThrow().getVersion();
        var before = encounterQueries.getTimeline(encounterId);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            switch (workflow) {
                case "handover" -> assign(replacementId, first.id(), ENTERED.plusHours(1));
                case "transfer" -> encounters.transferEncounter(encounterId, otherDepartmentId, wardId, null, ENTERED.plusHours(1), "operator", currentLocationId(encounterId));
                case "discharge" -> encounters.dischargeEncounter(encounterId, ENTERED.plusHours(1), "operator", currentLocationId(encounterId));
            }
            entityManager.flush();
            throw new IllegalStateException("Failure after workflow was flushed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Failure after workflow was flushed");
        assertThat(encounterQueries.getTimeline(encounterId)).isEqualTo(before);
        assertThat(service.getHistory(encounterId)).singleElement().satisfies(item -> {
            assertThat(item.getId()).isEqualTo(first.id());
            assertThat(item.getEndedAt()).isNull();
            assertThat(item.getEndedBy()).isNull();
            assertThat(item.getVersion()).isEqualTo(originalVersion);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @WithMockUser(username = "Case.Operator", roles = "OPERATOR")
    void transferAndDischargeUseTheSessionOperatorForAssignmentClosure(boolean discharge) throws Exception {
        assign(physicianId, null, ENTERED);
        String path = discharge ? "discharges" : "transfers";
        String request = discharge
                ? "{\"dischargedAt\":\"2025-09-01T11:00:00-04:00\",\"operator\":\"forged\",\"expectedLocationId\":\"%s\"}".formatted(locationId)
                : "{\"departmentId\":\"%s\",\"wardId\":\"%s\",\"transferredAt\":\"2025-09-01T11:00:00-04:00\",\"operator\":\"forged\",\"expectedLocationId\":\"%s\"}"
                    .formatted(otherDepartmentId, wardId, locationId);
        mvc.perform(post("/api/v1/encounters/{id}/" + path, encounterId).with(csrf())
                        .contentType("application/json").content(request))
                .andExpect(status().is(discharge ? 200 : 201));
        assertThat(service.getHistory(encounterId).getFirst().getEndedBy()).isEqualTo("Case.Operator");
    }

    @Test
    @WithMockUser(username = "operator", roles = "OPERATOR")
    void conflictingDischargeTimeReturnsAConflictAndKeepsTheBedOccupied() throws Exception {
        assign(physicianId, null, ENTERED.plusHours(1));
        var before = encounterQueries.getTimeline(encounterId);
        mvc.perform(post("/api/v1/encounters/{id}/discharges", encounterId).with(csrf())
                        .contentType("application/json").content("{\"dischargedAt\":\"2025-09-01T10:00:00-04:00\",\"expectedLocationId\":\"%s\"}".formatted(locationId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Physician assignment conflict"));
        assertThat(encounterQueries.getTimeline(encounterId)).isEqualTo(before);
    }

    private PhysicianAssignmentResponse assign(UUID physician, UUID expected, OffsetDateTime time) {
        return service.assign(encounterId, physician, locationId, expected, time, "Assignment.Clerk");
    }

    private UUID admit() {
        var patient = patients.createPatient("MRN-" + UUID.randomUUID(), "Test", "Patient", LocalDate.of(1990, 1, 1));
        return encounters.admitPatient(patient.id(), "ENC-" + UUID.randomUUID(), ENTERED.minusHours(1)).id();
    }

    private UUID currentLocationId(UUID encounterId) {
        return locations.findByEncounter_IdAndEndedAtIsNull(encounterId)
                .map(location -> location.getId()).orElse(null);
    }
    private UUID currentDischargeId(UUID encounterId) {
        return encounterQueries.getDischarges(encounterId).stream()
                .filter(discharge -> discharge.cancelledAt() == null).findFirst().orElseThrow().id();
    }
}
