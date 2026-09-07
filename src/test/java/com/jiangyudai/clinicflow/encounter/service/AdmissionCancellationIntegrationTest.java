package com.jiangyudai.clinicflow.encounter.service;

import com.jayway.jsonpath.JsonPath;
import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.DuplicateEncounterNumberException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterLocationHistoryExistsException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
        "spring.datasource.url=jdbc:h2:mem:admission-cancellation-it;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AdmissionCancellationIntegrationTest {

    @Autowired
    private EncounterService encounterService;
    @Autowired
    private EncounterRepository encounterRepository;
    @Autowired
    private EncounterLocationRepository encounterLocationRepository;
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

    @Test
    void cancelsThroughApiAndPreservesDetailsWhenRepeated() throws Exception {
        CancellationData data = createCancellationData();

        MvcResult result = mockMvc.perform(post(
                        "/api/v1/encounters/{id}/admission-cancellations", data.encounterId()
                ).contentType("application/json").content(request(data.cancelledAt(), "first-clerk")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(data.encounterId().toString()))
                .andExpect(jsonPath("$.encounterNumber").value(data.encounterNumber()))
                .andExpect(jsonPath("$.patientId").value(data.patientId().toString()))
                .andExpect(jsonPath("$.status").value("ADMISSION_CANCELLED"))
                .andExpect(jsonPath("$.dischargedAt").value(nullValue()))
                .andExpect(jsonPath("$.admissionCancelledBy").value("first-clerk"))
                .andReturn();

        String cancelledAt = JsonPath.read(result.getResponse().getContentAsString(), "$.admissionCancelledAt");
        assertThat(OffsetDateTime.parse(cancelledAt).toInstant()).isEqualTo(data.cancelledAt().toInstant());

        mockMvc.perform(post("/api/v1/encounters/{id}/admission-cancellations", data.encounterId())
                        .contentType("application/json")
                        .content(request(data.cancelledAt().plusMinutes(1), "second-clerk")))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/encounters/{id}", data.encounterId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMISSION_CANCELLED"))
                .andExpect(jsonPath("$.admissionCancelledAt").value(cancelledAt))
                .andExpect(jsonPath("$.admissionCancelledBy").value("first-clerk"));

        assertCancelled(data, "first-clerk");
    }

    @Test
    void permitsANewAdmissionWhileRetainingTheCancelledEncounterNumber() {
        CancellationData data = createCancellationData();
        encounterService.cancelAdmission(data.encounterId(), data.cancelledAt(), "demo-clerk");

        Encounter readmission = encounterService.admitPatient(
                data.patientId(), "ENC-" + UUID.randomUUID(), data.cancelledAt().plusHours(1)
        );

        assertThat(readmission.getId()).isNotEqualTo(data.encounterId());
        assertThat(readmission.getStatus()).isEqualTo(EncounterStatus.ADMITTED);
        assertThat(readmission.getAdmissionCancelledAt()).isNull();
        assertThat(readmission.getAdmissionCancelledBy()).isNull();
        assertThatThrownBy(() -> encounterService.admitPatient(
                data.patientId(), data.encounterNumber(), data.cancelledAt().plusHours(1)
        )).isInstanceOf(DuplicateEncounterNumberException.class);

        assertCancelled(data, "demo-clerk");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsAnyLocationHistoryEvenIfStatusStillSaysAdmitted(boolean closed) {
        CancellationData data = createCancellationData();
        transactions.executeWithoutResult(status -> {
            // Inconsistent state must not allow an existing care history to be cancelled.
            EncounterLocation location = new EncounterLocation(
                    encounterRepository.findById(data.encounterId()).orElseThrow(),
                    entityManager.find(Department.class, data.departmentId()),
                    entityManager.find(Ward.class, data.wardId()),
                    entityManager.find(Bed.class, data.bedId()),
                    data.admittedAt()
            );
            if (closed) {
                location.endAt(data.cancelledAt());
            }
            entityManager.persist(location);
        });

        assertThatThrownBy(() ->
                encounterService.cancelAdmission(data.encounterId(), data.cancelledAt(), "demo-clerk")
        ).isInstanceOf(EncounterLocationHistoryExistsException.class);

        transactions.executeWithoutResult(status -> {
            assertNotCancelled(data, EncounterStatus.ADMITTED);
            List<EncounterLocation> history = encounterLocationRepository
                    .findAllByEncounter_IdOrderByStartedAtAsc(data.encounterId());
            assertThat(history).hasSize(1);
            assertThat(history.getFirst().getEndedAt()).isEqualTo(closed ? data.cancelledAt() : null);
            assertThat(history.getFirst().getBed().getId()).isEqualTo(data.bedId());
        });
    }

    @Test
    void rollsBackCancellationDetailsAndStatusAfterFlush() {
        CancellationData data = createCancellationData();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            encounterService.cancelAdmission(data.encounterId(), data.cancelledAt(), "demo-clerk");
            entityManager.flush();
            throw new IllegalStateException("Simulated failure after cancellation");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated failure after cancellation");

        transactions.executeWithoutResult(status -> {
            assertNotCancelled(data, EncounterStatus.ADMITTED);
            assertThat(encounterLocationRepository.existsByEncounter_Id(data.encounterId())).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentDepartmentAdmissionOrCancellationWaitsThenRejectsCancelledEncounter(boolean enterDepartment)
            throws Exception {
        CancellationData data = createCancellationData();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> secondAttempt = transactions.execute(status -> {
                encounterService.cancelAdmission(data.encounterId(), data.cancelledAt(), "first-clerk");
                entityManager.flush();
                int sessionId = currentSessionId();
                Future<?> attempt = executor.submit(() -> {
                    if (enterDepartment) {
                        admitToDepartment(data);
                    } else {
                        encounterService.cancelAdmission(data.encounterId(), data.cancelledAt(), "second-clerk");
                    }
                });
                awaitBlockedWorkflow(sessionId, attempt);
                return attempt;
            });

            assertThat(secondAttempt).isNotNull();
            assertThatThrownBy(() -> secondAttempt.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InvalidEncounterStatusException.class);

            assertCancelled(data, "first-clerk");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cancellationWaitingForDepartmentAdmissionRejectsTheUpdatedState() throws Exception {
        CancellationData data = createCancellationData();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> cancellation = transactions.execute(status -> {
                admitToDepartment(data);
                entityManager.flush();
                int sessionId = currentSessionId();
                Future<?> attempt = executor.submit(() ->
                        encounterService.cancelAdmission(data.encounterId(), data.cancelledAt(), "demo-clerk")
                );
                awaitBlockedWorkflow(sessionId, attempt);
                return attempt;
            });

            assertThat(cancellation).isNotNull();
            assertThatThrownBy(() -> cancellation.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InvalidEncounterStatusException.class);

            transactions.executeWithoutResult(status -> {
                assertNotCancelled(data, EncounterStatus.IN_DEPARTMENT);
                List<EncounterLocation> history = encounterLocationRepository
                        .findAllByEncounter_IdOrderByStartedAtAsc(data.encounterId());
                assertThat(history).hasSize(1);
                assertThat(history.getFirst().getEndedAt()).isNull();
                assertThat(history.getFirst().getBed().getId()).isEqualTo(data.bedId());
                assertThat(encounterLocationRepository.existsByBed_IdAndEndedAtIsNull(data.bedId())).isTrue();
            });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertCancelled(CancellationData data, String operator) {
        transactions.executeWithoutResult(status -> {
            Encounter encounter = encounterRepository.findById(data.encounterId()).orElseThrow();
            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.ADMISSION_CANCELLED);
            assertThat(encounter.getAdmissionCancelledAt()).isEqualTo(data.cancelledAt());
            assertThat(encounter.getAdmissionCancelledBy()).isEqualTo(operator);
            assertThat(encounter.getPatient().getId()).isEqualTo(data.patientId());
            assertThat(encounter.getEncounterNumber()).isEqualTo(data.encounterNumber());
            assertThat(encounter.getAdmittedAt()).isEqualTo(data.admittedAt());
            assertThat(encounter.getDischargedAt()).isNull();
            assertThat(encounterLocationRepository.existsByEncounter_Id(data.encounterId())).isFalse();
            assertThat(encounterLocationRepository.existsByBed_IdAndEndedAtIsNull(data.bedId())).isFalse();
        });
    }

    private void assertNotCancelled(CancellationData data, EncounterStatus expectedStatus) {
        Encounter encounter = encounterRepository.findById(data.encounterId()).orElseThrow();
        assertThat(encounter.getStatus()).isEqualTo(expectedStatus);
        assertThat(encounter.getAdmissionCancelledAt()).isNull();
        assertThat(encounter.getAdmissionCancelledBy()).isNull();
    }

    private void admitToDepartment(CancellationData data) {
        encounterService.admitToDepartment(
                data.encounterId(), data.departmentId(), data.wardId(), data.bedId(), data.cancelledAt()
        );
    }

    private int currentSessionId() {
        return ((Number) entityManager.createNativeQuery("select session_id()").getSingleResult()).intValue();
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
                throw new AssertionError("Concurrent workflow finished without waiting for the encounter lock");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while checking the encounter lock", exception);
            }
        }
        throw new AssertionError("Concurrent workflow was not observed waiting for the encounter lock");
    }

    private String request(OffsetDateTime cancelledAt, String cancelledBy) {
        return """
                {"cancelledAt": "%s", "cancelledBy": "%s"}
                """.formatted(cancelledAt, cancelledBy);
    }

    private CancellationData createCancellationData() {
        CancellationData data = transactions.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            OffsetDateTime admittedAt = OffsetDateTime.now().minusDays(2).truncatedTo(ChronoUnit.SECONDS);
            Patient patient = new Patient("MRN-" + suffix, "Test", "Patient", LocalDate.of(1990, 5, 14));
            Department department = new Department("DEPT-" + suffix, "Cardiology");
            Ward ward = new Ward("WARD-" + suffix, "Cardiology Ward");
            Bed bed = new Bed("01", ward);
            entityManager.persist(patient);
            entityManager.persist(department);
            entityManager.persist(ward);
            entityManager.persist(bed);
            entityManager.flush();
            Encounter encounter = encounterService.admitPatient(patient.getId(), "ENC-" + suffix, admittedAt);

            return new CancellationData(
                    patient.getId(), encounter.getId(), encounter.getEncounterNumber(),
                    department.getId(), ward.getId(), bed.getId(), admittedAt, admittedAt.plusHours(1)
            );
        });
        if (data == null) {
            throw new IllegalStateException("Cancellation test data was not created");
        }
        return data;
    }

    private record CancellationData(
            UUID patientId,
            UUID encounterId,
            String encounterNumber,
            UUID departmentId,
            UUID wardId,
            UUID bedId,
            OffsetDateTime admittedAt,
            OffsetDateTime cancelledAt
    ) {
    }
}
