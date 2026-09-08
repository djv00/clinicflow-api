package com.jiangyudai.clinicflow.encounter.service;

import com.jayway.jsonpath.JsonPath;
import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterDischarge;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.ActiveEncounterExistsException;
import com.jiangyudai.clinicflow.encounter.exception.BedOccupiedException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;
import com.jiangyudai.clinicflow.encounter.repository.EncounterDischargeRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterLocationRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:discharge-cancellation-it;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class DischargeCancellationIntegrationTest {

    private static final OffsetDateTime ADMITTED_AT = OffsetDateTime.parse("2025-09-01T09:00:00-04:00");
    private static final OffsetDateTime DISCHARGED_AT = ADMITTED_AT.plusDays(1);
    private static final OffsetDateTime CANCELLED_AT = DISCHARGED_AT.plusHours(1);

    @Autowired
    private EncounterService encounterService;
    @Autowired
    private PatientService patientService;
    @Autowired
    private EncounterRepository encounterRepository;
    @Autowired
    private EncounterLocationRepository encounterLocationRepository;
    @Autowired
    private EncounterDischargeRepository encounterDischargeRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MockMvc mockMvc;
    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void restoresTheDischargeLocationWithOrWithoutABedAndPreservesAudit(boolean withBed) throws Exception {
        CancellationData data = createData(withBed);

        mockMvc.perform(post("/api/v1/encounters/{id}/discharge-cancellations", data.encounterId())
                        .contentType("application/json").content(request(CANCELLED_AT, "first-clerk")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(data.encounterId().toString()))
                .andExpect(jsonPath("$.patientId").value(data.patientId().toString()))
                .andExpect(jsonPath("$.status").value("IN_DEPARTMENT"))
                .andExpect(jsonPath("$.dischargedAt").value(nullValue()));

        assertRestored(data);
        String response = mockMvc.perform(get("/api/v1/encounters/{id}/discharges", data.encounterId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].locationId").value(data.locationId().toString()))
                .andExpect(jsonPath("$[0].encounterId").value(data.encounterId().toString()))
                .andExpect(jsonPath("$[0].cancelledBy").value("first-clerk"))
                .andExpect(jsonPath("$[0].restoredLocationId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String cancelledAt = JsonPath.read(response, "$[0].cancelledAt");
        String dischargedAt = JsonPath.read(response, "$[0].dischargedAt");
        assertThat(OffsetDateTime.parse(cancelledAt).toInstant()).isEqualTo(CANCELLED_AT.toInstant());
        assertThat(OffsetDateTime.parse(dischargedAt).toInstant()).isEqualTo(DISCHARGED_AT.toInstant());

        mockMvc.perform(post("/api/v1/encounters/{id}/discharge-cancellations", data.encounterId())
                        .contentType("application/json").content(request(CANCELLED_AT, "second-clerk")))
                .andExpect(status().isConflict());
        assertThat(mockMvc.perform(get("/api/v1/encounters/{id}/discharges", data.encounterId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(response);
        assertRestored(data);
    }

    @Test
    void recordsEachDischargeAndCancellationSeparately() {
        CancellationData data = createData(true);
        cancel(data);
        EncounterDischarge first = encounterService.getDischarges(data.encounterId()).getFirst();
        UUID restoredId = first.getRestoredLocation().getId();

        encounterService.dischargeEncounter(data.encounterId(), CANCELLED_AT.plusHours(1));
        encounterService.cancelDischarge(data.encounterId(), CANCELLED_AT.plusHours(2), "second-clerk");

        List<EncounterDischarge> discharges = encounterService.getDischarges(data.encounterId());
        assertThat(discharges).hasSize(2);
        assertThat(discharges.getFirst().getId()).isEqualTo(first.getId());
        assertThat(discharges.getFirst().getCancelledBy()).isEqualTo("first-clerk");
        assertThat(discharges.getFirst().getCancelledAt()).isEqualTo(CANCELLED_AT);
        assertThat(discharges.getLast().getLocation().getId()).isEqualTo(restoredId);
        assertThat(discharges.getLast().getCancelledBy()).isEqualTo("second-clerk");
        assertThat(discharges.getLast().getCancelledAt()).isEqualTo(CANCELLED_AT.plusHours(2));
        assertThat(discharges.getLast().getRestoredLocation().getId()).isNotEqualTo(restoredId);
        transactions.executeWithoutResult(status -> {
            List<EncounterLocation> history = encounterLocationRepository
                    .findAllByEncounter_IdOrderByStartedAtAsc(data.encounterId());
            assertThat(history).hasSize(3);
            assertThat(history.getFirst().getEndedAt()).isEqualTo(DISCHARGED_AT);
            assertThat(history.get(1).getEndedAt()).isEqualTo(CANCELLED_AT.plusHours(1));
            assertThat(history.getLast().getEndedAt()).isNull();
        });
    }

    @Test
    void restoresTheExplicitDischargeLocationWhenTransferAndDischargeShareATimestamp() {
        CancellationData data = createData(true);
        cancel(data);
        EncounterLocation transferred = encounterService.transferEncounter(
                data.encounterId(), data.departmentId(), data.wardId(), null, CANCELLED_AT
        );
        encounterService.dischargeEncounter(data.encounterId(), CANCELLED_AT);
        encounterService.cancelDischarge(data.encounterId(), CANCELLED_AT, "second-clerk");

        EncounterDischarge discharge = encounterService.getDischarges(data.encounterId()).getLast();
        assertThat(discharge.getLocation().getId()).isEqualTo(transferred.getId());
        transactions.executeWithoutResult(status -> {
            EncounterLocation current = encounterLocationRepository
                    .findByEncounter_IdAndEndedAtIsNull(data.encounterId()).orElseThrow();
            assertThat(current.getBed()).isNull();
            assertThat(current.getStartedAt()).isEqualTo(CANCELLED_AT);
            assertThat(encounterLocationRepository.existsByBed_IdAndEndedAtIsNull(data.bedId())).isFalse();
        });
    }

    @Test
    void rollsBackCancellationAndTheNewLocationAfterFlush() {
        CancellationData data = createData(true);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            cancel(data);
            entityManager.flush();
            throw new IllegalStateException("Simulated cancellation failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated cancellation failure");
        assertDischarged(data);
    }

    @Test
    void rollsBackTheNewDischargeRecordTogetherWithEncounterAndLocation() {
        CancellationData data = createData(true);
        cancel(data);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            encounterService.dischargeEncounter(data.encounterId(), CANCELLED_AT.plusHours(1));
            entityManager.flush();
            throw new IllegalStateException("Simulated discharge failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated discharge failure");
        assertRestored(data);
        assertThat(encounterService.getDischarges(data.encounterId())).hasSize(1);
    }

    @Test
    void rejectsCancellationBeforeDischargeWithoutChangingHistory() throws Exception {
        CancellationData data = createData(true);
        mockMvc.perform(post("/api/v1/encounters/{id}/discharge-cancellations", data.encounterId())
                        .contentType("application/json").content(request(DISCHARGED_AT.minusSeconds(1), "first-clerk")))
                .andExpect(status().isBadRequest());
        assertDischarged(data);
    }

    @ParameterizedTest
    @ValueSource(strings = {"departments", "wards", "beds"})
    void rejectsInactiveOriginalLocation(String table) throws Exception {
        CancellationData data = createData(true);
        UUID id = switch (table) {
            case "departments" -> data.departmentId();
            case "wards" -> data.wardId();
            default -> data.bedId();
        };
        transactions.executeWithoutResult(status -> entityManager
                .createNativeQuery("update " + table + " set active = false where id = ?1")
                .setParameter(1, id).executeUpdate());
        mockMvc.perform(post("/api/v1/encounters/{id}/discharge-cancellations", data.encounterId())
                        .contentType("application/json").content(request(CANCELLED_AT, "first-clerk")))
                .andExpect(status().isBadRequest());
        assertDischarged(data);
    }

    @Test
    void rejectsReadmittedPatientAndReturnsAnEmptyHistoryForTheNewEncounter() throws Exception {
        CancellationData data = createData(true);
        Encounter readmission = readmit(data);
        mockMvc.perform(post("/api/v1/encounters/{id}/discharge-cancellations", data.encounterId())
                        .contentType("application/json").content(request(CANCELLED_AT, "first-clerk")))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/encounters/{id}/discharges", readmission.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/encounters/{id}/discharges", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        assertDischarged(data);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void serializesReadmissionAndCancellationForTheSamePatient(boolean cancellationFirst) throws Exception {
        CancellationData data = createData(true);
        assertWaitingWorkflowFails(
                () -> { if (cancellationFirst) cancel(data); else readmit(data); },
                () -> { if (cancellationFirst) readmit(data); else cancel(data); },
                ActiveEncounterExistsException.class
        );
        if (cancellationFirst) {
            assertRestored(data);
        } else {
            assertDischarged(data);
        }
        transactions.executeWithoutResult(status -> {
            Long active = entityManager.createQuery("""
                    select count(e) from Encounter e where e.patient.id = :patientId
                    and e.status in :states
                    """, Long.class).setParameter("patientId", data.patientId())
                    .setParameter("states", List.of(EncounterStatus.ADMITTED, EncounterStatus.IN_DEPARTMENT))
                    .getSingleResult();
            assertThat(active).isEqualTo(1L);
        });
    }

    @Test
    void serializesDuplicateCancellationAndReadsTheCommittedEncounterState() throws Exception {
        CancellationData data = createData(true);
        assertWaitingWorkflowFails(() -> cancel(data), () -> cancel(data), InvalidEncounterStatusException.class);
        assertRestored(data);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void serializesCancellationWithAnotherPatientsBedAssignment(boolean cancellationFirst) throws Exception {
        CancellationData data = createData(true);
        UUID otherEncounterId = createOtherEncounter();
        Runnable occupyBed = () -> encounterService.admitToDepartment(
                otherEncounterId, data.departmentId(), data.wardId(), data.bedId(), CANCELLED_AT
        );
        assertWaitingWorkflowFails(
                cancellationFirst ? () -> cancel(data) : occupyBed,
                cancellationFirst ? occupyBed : () -> cancel(data),
                BedOccupiedException.class
        );
        if (cancellationFirst) {
            assertRestored(data);
        } else {
            assertDischarged(data);
            mockMvc.perform(post("/api/v1/encounters/{id}/discharge-cancellations", data.encounterId())
                            .contentType("application/json").content(request(CANCELLED_AT, "first-clerk")))
                    .andExpect(status().isConflict());
        }
        transactions.executeWithoutResult(status -> {
            UUID owner = encounterLocationRepository.findByEncounter_IdAndEndedAtIsNull(
                    cancellationFirst ? data.encounterId() : otherEncounterId
            ).orElseThrow().getBed().getId();
            assertThat(owner).isEqualTo(data.bedId());
        });
    }

    @Test
    void patientWriteLockRequiresAWorkflowTransaction() {
        assertThatThrownBy(() -> patientService.getPatientForUpdate(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private void assertDischarged(CancellationData data) {
        transactions.executeWithoutResult(status -> {
            Encounter encounter = encounterRepository.findById(data.encounterId()).orElseThrow();
            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.DISCHARGED);
            assertThat(encounter.getDischargedAt()).isEqualTo(DISCHARGED_AT);
            List<EncounterLocation> history = encounterLocationRepository
                    .findAllByEncounter_IdOrderByStartedAtAsc(data.encounterId());
            assertThat(history).hasSize(1);
            assertThat(history.getFirst().getId()).isEqualTo(data.locationId());
            assertThat(history.getFirst().getEndedAt()).isEqualTo(DISCHARGED_AT);
            EncounterDischarge discharge = encounterDischargeRepository
                    .findByEncounter_IdAndCancelledAtIsNull(data.encounterId()).orElseThrow();
            assertThat(discharge.getCancelledBy()).isNull();
            assertThat(discharge.getRestoredLocation()).isNull();
        });
    }

    private void assertRestored(CancellationData data) {
        transactions.executeWithoutResult(status -> {
            Encounter encounter = encounterRepository.findById(data.encounterId()).orElseThrow();
            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
            assertThat(encounter.getDischargedAt()).isNull();
            assertThat(encounter.getAdmittedAt()).isEqualTo(ADMITTED_AT);
            List<EncounterLocation> history = encounterLocationRepository
                    .findAllByEncounter_IdOrderByStartedAtAsc(data.encounterId());
            assertThat(history).hasSize(2);
            assertThat(history.getFirst().getId()).isEqualTo(data.locationId());
            assertThat(history.getFirst().getEndedAt()).isEqualTo(DISCHARGED_AT);
            EncounterLocation restored = history.getLast();
            assertThat(restored.getStartedAt()).isEqualTo(CANCELLED_AT);
            assertThat(restored.getEndedAt()).isNull();
            assertThat(restored.getDepartment().getId()).isEqualTo(data.departmentId());
            assertThat(restored.getWard().getId()).isEqualTo(data.wardId());
            assertThat(restored.getBed() == null ? null : restored.getBed().getId()).isEqualTo(data.bedId());
            assertThat(encounterDischargeRepository.findByEncounter_IdAndCancelledAtIsNull(data.encounterId())).isEmpty();
        });
    }

    private void assertWaitingWorkflowFails(Runnable first, Runnable second,
                                           Class<? extends Throwable> expectedCause) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> attempt = transactions.execute(status -> {
                first.run();
                entityManager.flush();
                int sessionId = ((Number) entityManager.createNativeQuery("select session_id()")
                        .getSingleResult()).intValue();
                Future<?> waiting = executor.submit(second);
                awaitBlockedWorkflow(sessionId, waiting);
                return waiting;
            });
            assertThat(attempt).isNotNull();
            assertThatThrownBy(() -> attempt.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(expectedCause);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitBlockedWorkflow(int sessionId, Future<?> attempt) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Number waiting = (Number) entityManager.createNativeQuery("""
                    select count(*) from information_schema.sessions where blocker_id = ?1
                    """).setParameter(1, sessionId).getSingleResult();
            if (waiting.intValue() > 0) {
                return;
            }
            if (attempt.isDone()) {
                throw new AssertionError("Concurrent workflow finished without waiting for the workflow lock");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while checking the workflow lock", exception);
            }
        }
        throw new AssertionError("Concurrent workflow was not observed waiting for the workflow lock");
    }

    private void cancel(CancellationData data) {
        encounterService.cancelDischarge(data.encounterId(), CANCELLED_AT, "first-clerk");
    }

    private Encounter readmit(CancellationData data) {
        return encounterService.admitPatient(data.patientId(), "ENC-" + UUID.randomUUID(), CANCELLED_AT);
    }

    private String request(OffsetDateTime cancelledAt, String cancelledBy) {
        return """
                {"cancelledAt": "%s", "cancelledBy": "%s"}
                """.formatted(cancelledAt, cancelledBy);
    }

    private UUID createOtherEncounter() {
        return transactions.execute(status -> {
            Patient patient = createPatient();
            return encounterService.admitPatient(patient.getId(), "ENC-" + UUID.randomUUID(), ADMITTED_AT).getId();
        });
    }

    private Patient createPatient() {
        Patient patient = new Patient("MRN-" + UUID.randomUUID(), "Test", "Patient", LocalDate.of(1990, 5, 14));
        entityManager.persist(patient);
        entityManager.flush();
        return patient;
    }

    private CancellationData createData(boolean withBed) {
        return transactions.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Patient patient = createPatient();
            Department department = new Department("DEPT-" + suffix, "Cardiology");
            Ward ward = new Ward("WARD-" + suffix, "Cardiology Ward");
            entityManager.persist(department);
            entityManager.persist(ward);
            Bed bed = withBed ? new Bed("01", ward) : null;
            if (bed != null) {
                entityManager.persist(bed);
            }
            entityManager.flush();
            Encounter encounter = encounterService.admitPatient(patient.getId(), "ENC-" + suffix, ADMITTED_AT);
            EncounterLocation location = encounterService.admitToDepartment(
                    encounter.getId(), department.getId(), ward.getId(), bed == null ? null : bed.getId(),
                    ADMITTED_AT.plusHours(1)
            );
            encounterService.dischargeEncounter(encounter.getId(), DISCHARGED_AT);
            return new CancellationData(patient.getId(), encounter.getId(), department.getId(), ward.getId(),
                    bed == null ? null : bed.getId(), location.getId());
        });
    }

    private record CancellationData(UUID patientId, UUID encounterId, UUID departmentId,
                                    UUID wardId, UUID bedId, UUID locationId) {
    }
}
