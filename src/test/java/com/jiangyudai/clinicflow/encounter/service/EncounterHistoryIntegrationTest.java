package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.BedHistoryConflictException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterHistoryConflictException;
import com.jiangyudai.clinicflow.encounter.repository.EncounterDischargeRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterLocationRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.patient.entity.Patient;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:encounter-history-it;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class EncounterHistoryIntegrationTest {

    private static final OffsetDateTime ADMITTED_AT = OffsetDateTime.parse("2025-09-01T08:00:00-04:00");
    private static final OffsetDateTime STARTED_AT = ADMITTED_AT.plusHours(1);
    private static final OffsetDateTime DISCHARGED_AT = ADMITTED_AT.plusDays(1).plusHours(4);

    @Autowired
    private EncounterService encounterService;
    @Autowired
    private EncounterRepository encounterRepository;
    @Autowired
    private EncounterLocationRepository locationRepository;
    @Autowired
    private EncounterDischargeRepository dischargeRepository;
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
    @ValueSource(ints = {-48, -3})
    void rejectsAdmissionBeforeAnExistingDischarge(int hoursFromDischarge) throws Exception {
        HistoryData data = dischargedPatient();
        String number = "ENC-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/encounters").contentType("application/json").content("""
                        {"patientId": "%s", "encounterNumber": "%s", "admittedAt": "%s"}
                        """.formatted(data.patientId(), number, DISCHARGED_AT.plusHours(hoursFromDischarge))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Encounter history conflict"));

        assertThat(encounterRepository.existsByEncounterNumber(number)).isFalse();
        assertThat(encounterService.getEncounter(data.encounterId()).getStatus()).isEqualTo(EncounterStatus.DISCHARGED);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void admitsAtOrAfterDischargeUsingTheSameInstantAcrossOffsets(int hoursFromDischarge) {
        HistoryData data = dischargedPatient();
        OffsetDateTime admittedAt = DISCHARGED_AT.plusHours(hoursFromDischarge).withOffsetSameInstant(ZoneOffset.UTC);

        Encounter encounter = admit(data.patientId(), admittedAt);

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.ADMITTED);
        assertThat(encounter.getAdmittedAt()).isEqualTo(admittedAt);
    }

    @Test
    void cancelledAdmissionDoesNotBlockAnEarlierReplacement() {
        HistoryData data = createData();
        encounterService.cancelAdmission(data.encounterId(), DISCHARGED_AT, "test-clerk");

        Encounter replacement = admit(data.patientId(), ADMITTED_AT.minusHours(1));

        assertThat(replacement.getStatus()).isEqualTo(EncounterStatus.ADMITTED);
        assertThat(encounterService.getEncounter(data.encounterId()).getStatus())
                .isEqualTo(EncounterStatus.ADMISSION_CANCELLED);
    }

    @ParameterizedTest
    @ValueSource(ints = {-30, -2})
    void rejectsBackdatedBedAssignmentWithoutEnteringDepartment(int hoursFromDischarge) throws Exception {
        HistoryData data = dischargedPatient();
        UUID other = newEncounter(ADMITTED_AT.minusDays(1));

        mockMvc.perform(post("/api/v1/encounters/{id}/department-admissions", other)
                        .contentType("application/json").content("""
                                {"departmentId": "%s", "wardId": "%s", "bedId": "%s", "startedAt": "%s"}
                                """.formatted(data.departmentId(), data.wardId(), data.bedId(),
                                DISCHARGED_AT.plusHours(hoursFromDischarge))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Encounter history conflict"));

        assertThat(encounterService.getEncounter(other).getStatus()).isEqualTo(EncounterStatus.ADMITTED);
        assertThat(locationRepository.existsByEncounter_Id(other)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void reusesBedAtOrAfterItsPreviousEnd(int hoursFromDischarge) {
        HistoryData data = dischargedPatient();
        UUID other = newEncounter(ADMITTED_AT);
        OffsetDateTime startedAt = DISCHARGED_AT.plusHours(hoursFromDischarge).withOffsetSameInstant(ZoneOffset.UTC);

        EncounterLocation location = enter(other, data, data.bedId(), startedAt);

        assertThat(location.getStartedAt()).isEqualTo(startedAt);
        assertThat(location.getBed().getId()).isEqualTo(data.bedId());
    }

    @Test
    void bedHistoryDoesNotBlockAnotherBedOrAdmissionWithoutABed() {
        HistoryData data = dischargedPatient();
        UUID other = newEncounter(ADMITTED_AT);
        UUID withoutBed = newEncounter(ADMITTED_AT);

        assertThat(enter(other, data, data.otherBedId(), STARTED_AT).getBed().getId()).isEqualTo(data.otherBedId());
        assertThat(enter(withoutBed, data, null, STARTED_AT).getBed()).isNull();
    }

    @Test
    void zeroDurationLocationDoesNotOccupyAnyHistoricalInterval() {
        HistoryData data = createData();
        enter(data.encounterId(), data, data.bedId(), STARTED_AT);
        encounterService.dischargeEncounter(data.encounterId(), STARTED_AT);
        UUID other = newEncounter(ADMITTED_AT);

        assertThat(enter(other, data, data.bedId(), ADMITTED_AT).getBed().getId()).isEqualTo(data.bedId());
    }

    @Test
    void failedTransferPreservesCurrentLocationAndCanRetryAtTheBoundary() {
        HistoryData data = dischargedPatient();
        UUID other = newEncounter(ADMITTED_AT);
        EncounterLocation current = enter(other, data, data.otherBedId(), STARTED_AT);

        assertThatThrownBy(() -> encounterService.transferEncounter(
                other, data.departmentId(), data.wardId(), data.bedId(), DISCHARGED_AT.minusHours(1)
        )).isInstanceOf(BedHistoryConflictException.class);

        EncounterLocation unchanged = locationRepository.findByEncounter_IdAndEndedAtIsNull(other).orElseThrow();
        assertThat(unchanged.getId()).isEqualTo(current.getId());
        assertThat(locationRepository.findAllByEncounter_IdOrderByStartedAtAsc(other)).hasSize(1);
        EncounterLocation next = encounterService.transferEncounter(
                other, data.departmentId(), data.wardId(), data.bedId(), DISCHARGED_AT
        );
        assertThat(next.getBed().getId()).isEqualTo(data.bedId());
        assertThat(locationRepository.findById(current.getId()).orElseThrow().getEndedAt()).isEqualTo(DISCHARGED_AT);
    }

    @Test
    void bedHistoryConflictRollsBackCancellationAuditAndRestoredLocation() {
        HistoryData data = dischargedPatient();
        UUID other = newEncounter(DISCHARGED_AT);
        enter(other, data, data.bedId(), DISCHARGED_AT.plusHours(1));
        encounterService.dischargeEncounter(other, DISCHARGED_AT.plusHours(3));

        assertThatThrownBy(() -> encounterService.cancelDischarge(
                data.encounterId(), DISCHARGED_AT.plusHours(2), "test-clerk"
        )).isInstanceOf(BedHistoryConflictException.class);

        transactions.executeWithoutResult(status -> {
            Encounter encounter = encounterRepository.findById(data.encounterId()).orElseThrow();
            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.DISCHARGED);
            assertThat(encounter.getDischargedAt()).isEqualTo(DISCHARGED_AT);
            assertThat(locationRepository.findAllByEncounter_IdOrderByStartedAtAsc(data.encounterId())).hasSize(1);
            var discharge = dischargeRepository.findByEncounter_IdAndCancelledAtIsNull(data.encounterId()).orElseThrow();
            assertThat(discharge.getCancelledAt()).isNull();
            assertThat(discharge.getCancelledBy()).isNull();
            assertThat(discharge.getRestoredLocation()).isNull();
        });

        assertThat(encounterService.cancelDischarge(data.encounterId(), DISCHARGED_AT.plusHours(3), "retry-clerk")
                .getStatus()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
    }

    @Test
    void waitingAdmissionSeesACompletedEncounterAfterThePatientLockIsReleased() throws Exception {
        HistoryData data = createData();

        assertWaitingWorkflowFails(() -> {
            enter(data.encounterId(), data, data.bedId(), STARTED_AT);
            encounterService.dischargeEncounter(data.encounterId(), DISCHARGED_AT);
        }, () -> admit(data.patientId(), DISCHARGED_AT.minusHours(1)),
                EncounterHistoryConflictException.class, data.patientId());
    }

    @Test
    void waitingBedAssignmentSeesClosedHistoryAfterTheBedLockIsReleased() throws Exception {
        HistoryData data = createData();
        UUID other = newEncounter(ADMITTED_AT);

        assertWaitingWorkflowFails(() -> {
            enter(data.encounterId(), data, data.bedId(), STARTED_AT);
            encounterService.dischargeEncounter(data.encounterId(), DISCHARGED_AT);
        }, () -> enter(other, data, data.bedId(), DISCHARGED_AT.minusHours(1)),
                BedHistoryConflictException.class, data.patientId());

        assertThat(locationRepository.existsByEncounter_Id(other)).isFalse();
        assertThat(encounterService.getEncounter(other).getStatus()).isEqualTo(EncounterStatus.ADMITTED);
    }

    private void assertWaitingWorkflowFails(Runnable first, Runnable second,
                                           Class<? extends Throwable> expectedCause, UUID patientId) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> attempt = transactions.execute(status -> {
                // Hold the same patient lock used by admission before completing care.
                entityManager.find(Patient.class, patientId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
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
            Number waiting = (Number) entityManager.createNativeQuery(
                    "select count(*) from information_schema.sessions where blocker_id = ?1"
            ).setParameter(1, sessionId).getSingleResult();
            if (waiting.intValue() > 0) {
                return;
            }
            assertThat(attempt.isDone()).as("workflow must wait for its lock").isFalse();
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while checking the workflow lock", exception);
            }
        }
        throw new AssertionError("Concurrent workflow did not wait for the workflow lock");
    }

    private Encounter admit(UUID patientId, OffsetDateTime admittedAt) {
        return encounterService.admitPatient(patientId, "ENC-" + UUID.randomUUID(), admittedAt);
    }

    private UUID newEncounter(OffsetDateTime admittedAt) {
        return transactions.execute(status -> admit(createPatient().getId(), admittedAt).getId());
    }

    private Patient createPatient() {
        Patient patient = new Patient("MRN-" + UUID.randomUUID(), "Test", "Patient", LocalDate.of(1990, 5, 14));
        entityManager.persist(patient);
        entityManager.flush();
        return patient;
    }

    private EncounterLocation enter(UUID encounterId, HistoryData data, UUID bedId, OffsetDateTime startedAt) {
        return encounterService.admitToDepartment(encounterId, data.departmentId(), data.wardId(), bedId, startedAt);
    }

    private HistoryData dischargedPatient() {
        HistoryData data = createData();
        enter(data.encounterId(), data, data.bedId(), STARTED_AT);
        encounterService.dischargeEncounter(data.encounterId(), DISCHARGED_AT);
        return data;
    }

    private HistoryData createData() {
        return transactions.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Patient patient = createPatient();
            Department department = new Department("DEPT-" + suffix, "Test Department");
            Ward ward = new Ward("WARD-" + suffix, "Test Ward");
            entityManager.persist(department);
            entityManager.persist(ward);
            Bed bed = new Bed("01", ward);
            Bed otherBed = new Bed("02", ward);
            entityManager.persist(bed);
            entityManager.persist(otherBed);
            entityManager.flush();
            return new HistoryData(patient.getId(), admit(patient.getId(), ADMITTED_AT).getId(),
                    department.getId(), ward.getId(), bed.getId(), otherBed.getId());
        });
    }

    private record HistoryData(UUID patientId, UUID encounterId, UUID departmentId,
                               UUID wardId, UUID bedId, UUID otherBedId) {
    }
}
