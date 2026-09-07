package com.jiangyudai.clinicflow.encounter.service;

import com.jayway.jsonpath.JsonPath;
import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidDischargeTimeException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:encounter-discharge-it;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class EncounterDischargeIntegrationTest {

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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void dischargesThroughApiAndRejectsRepeat(boolean withBed) throws Exception {
        DischargeData data = createDischargeData(withBed);
        String request = "{\"dischargedAt\": \"" + data.dischargedAt() + "\"}";

        MvcResult result = mockMvc.perform(post(
                        "/api/v1/encounters/{id}/discharges", data.encounterId()
                ).contentType("application/json").content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(data.encounterId().toString()))
                .andExpect(jsonPath("$.patientId").value(data.patientId().toString()))
                .andExpect(jsonPath("$.status").value("DISCHARGED"))
                .andReturn();

        String dischargedAt = JsonPath.read(
                result.getResponse().getContentAsString(), "$.dischargedAt"
        );
        assertThat(OffsetDateTime.parse(dischargedAt).toInstant())
                .isEqualTo(data.dischargedAt().toInstant());

        mockMvc.perform(get("/api/v1/encounters/{id}", data.encounterId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCHARGED"))
                .andExpect(jsonPath("$.dischargedAt").value(dischargedAt));

        mockMvc.perform(post("/api/v1/encounters/{id}/discharges", data.encounterId())
                        .contentType("application/json").content(request))
                .andExpect(status().isConflict());

        assertDischarged(data, 1);
    }

    @Test
    void releasesBedForAnotherPatientAndAllowsReadmission() {
        DischargeData first = createDischargeData(true);
        DischargeData second = createDischargeData(false);

        encounterService.dischargeEncounter(first.encounterId(), first.dischargedAt());
        encounterService.transferEncounter(
                second.encounterId(), first.departmentId(), first.wardId(),
                first.bedId(), second.dischargedAt()
        );

        Encounter readmission = encounterService.admitPatient(
                first.patientId(), "ENC-" + UUID.randomUUID(), first.dischargedAt().plusHours(1)
        );

        assertThat(readmission.getStatus()).isEqualTo(EncounterStatus.ADMITTED);
        assertThat(readmission.getId()).isNotEqualTo(first.encounterId());
        transactions.executeWithoutResult(status -> {
            EncounterLocation location = encounterLocationRepository
                    .findByEncounter_IdAndEndedAtIsNull(second.encounterId()).orElseThrow();
            assertThat(location.getBed().getId()).isEqualTo(first.bedId());
            assertThat(encounterLocationRepository
                    .existsByEncounter_IdAndEndedAtIsNull(first.encounterId())).isFalse();
            assertThat(encounterRepository.findById(first.encounterId()).orElseThrow().getStatus())
                    .isEqualTo(EncounterStatus.DISCHARGED);
        });
    }

    @Test
    void preservesEarlierHistoryWhenDischargedAfterTransfer() {
        DischargeData data = createDischargeData(true);
        OffsetDateTime transferredAt = data.startedAt().plusHours(1);
        encounterService.transferEncounter(
                data.encounterId(), data.departmentId(), data.wardId(),
                data.otherBedId(), transferredAt
        );

        encounterService.dischargeEncounter(data.encounterId(), data.dischargedAt());

        assertDischarged(data, 2);
        transactions.executeWithoutResult(status -> {
            List<EncounterLocation> history = encounterLocationRepository
                    .findAllByEncounter_IdOrderByStartedAtAsc(data.encounterId());
            assertThat(history.getFirst().getBed().getId()).isEqualTo(data.bedId());
            assertThat(history.getFirst().getStartedAt()).isEqualTo(data.startedAt());
            assertThat(history.getFirst().getEndedAt()).isEqualTo(transferredAt);
            assertThat(history.getLast().getBed().getId()).isEqualTo(data.otherBedId());
            assertThat(history.getLast().getStartedAt()).isEqualTo(transferredAt);
        });
    }

    @Test
    void rollsBackStatusAndLocationWhenTransactionFailsAfterFlush() {
        DischargeData data = createDischargeData(true);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            encounterService.dischargeEncounter(data.encounterId(), data.dischargedAt());
            entityManager.flush();
            throw new IllegalStateException("Simulated failure after discharge");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated failure after discharge");

        transactions.executeWithoutResult(status -> {
            Encounter encounter = encounterRepository.findById(data.encounterId()).orElseThrow();
            List<EncounterLocation> history = encounterLocationRepository
                    .findAllByEncounter_IdOrderByStartedAtAsc(data.encounterId());
            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
            assertThat(encounter.getDischargedAt()).isNull();
            assertThat(history).hasSize(1);
            assertThat(history.getFirst().getEndedAt()).isNull();
            assertThat(encounterLocationRepository.existsByBed_IdAndEndedAtIsNull(data.bedId()))
                    .isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentWorkflowWaitsForDischargeThenRejectsClosedEncounter(boolean transfer)
            throws Exception {
        DischargeData data = createDischargeData(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> secondAttempt = transactions.execute(status -> {
                encounterService.dischargeEncounter(data.encounterId(), data.dischargedAt());
                entityManager.flush();
                int sessionId = ((Number) entityManager.createNativeQuery("select session_id()")
                        .getSingleResult()).intValue();

                Future<?> attempt = executor.submit(() -> {
                    if (transfer) {
                        encounterService.transferEncounter(
                                data.encounterId(), data.departmentId(), data.wardId(),
                                data.otherBedId(), data.dischargedAt()
                        );
                    } else {
                        encounterService.dischargeEncounter(data.encounterId(), data.dischargedAt());
                    }
                });
                awaitBlockedWorkflow(sessionId, attempt);
                return attempt;
            });

            assertThat(secondAttempt).isNotNull();
            assertThatThrownBy(() -> secondAttempt.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InvalidEncounterStatusException.class);
            assertDischarged(data, 1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void dischargeWaitingForTransferValidatesTheNewLocationTime() throws Exception {
        DischargeData data = createDischargeData(true);
        OffsetDateTime transferredAt = data.startedAt().plusHours(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> discharge = transactions.execute(status -> {
                encounterService.transferEncounter(
                        data.encounterId(), data.departmentId(), data.wardId(),
                        data.otherBedId(), transferredAt
                );
                entityManager.flush();
                int sessionId = ((Number) entityManager.createNativeQuery("select session_id()")
                        .getSingleResult()).intValue();
                Future<?> attempt = executor.submit(() ->
                        encounterService.dischargeEncounter(data.encounterId(), data.startedAt())
                );
                awaitBlockedWorkflow(sessionId, attempt);
                return attempt;
            });

            assertThat(discharge).isNotNull();
            assertThatThrownBy(() -> discharge.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InvalidDischargeTimeException.class);

            transactions.executeWithoutResult(status -> {
                Encounter encounter = encounterRepository.findById(data.encounterId()).orElseThrow();
                List<EncounterLocation> history = encounterLocationRepository
                        .findAllByEncounter_IdOrderByStartedAtAsc(data.encounterId());
                assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
                assertThat(encounter.getDischargedAt()).isNull();
                assertThat(history).hasSize(2);
                assertThat(history.getFirst().getEndedAt()).isEqualTo(transferredAt);
                assertThat(history.getLast().getStartedAt()).isEqualTo(transferredAt);
                assertThat(history.getLast().getEndedAt()).isNull();
                assertThat(history.getLast().getBed().getId()).isEqualTo(data.otherBedId());
            });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertDischarged(DischargeData data, int historySize) {
        transactions.executeWithoutResult(status -> {
            Encounter encounter = encounterRepository.findById(data.encounterId()).orElseThrow();
            List<EncounterLocation> history = encounterLocationRepository
                    .findAllByEncounter_IdOrderByStartedAtAsc(data.encounterId());

            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.DISCHARGED);
            assertThat(encounter.getDischargedAt()).isEqualTo(data.dischargedAt());
            assertThat(history).hasSize(historySize);
            assertThat(history.getLast().getEndedAt()).isEqualTo(data.dischargedAt());
            assertThat(history).allSatisfy(location -> assertThat(location.getEndedAt()).isNotNull());
            assertThat(encounterLocationRepository
                    .findByEncounter_IdAndEndedAtIsNull(data.encounterId())).isEmpty();
            assertThat(encounterLocationRepository.existsByBed_IdAndEndedAtIsNull(data.bedId()))
                    .isFalse();
            assertThat(encounterLocationRepository.existsByBed_IdAndEndedAtIsNull(data.otherBedId()))
                    .isFalse();
        });
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

    private DischargeData createDischargeData(boolean withBed) {
        DischargeData data = transactions.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            OffsetDateTime admittedAt = OffsetDateTime.now().minusDays(3).truncatedTo(ChronoUnit.SECONDS);
            Patient patient = new Patient("MRN-" + suffix, "Test", "Patient", LocalDate.of(1990, 5, 14));
            Department department = new Department("DEPT-" + suffix, "Cardiology");
            Ward ward = new Ward("WARD-" + suffix, "Cardiology Ward");
            Bed bed = new Bed("01", ward);
            Bed otherBed = new Bed("02", ward);
            entityManager.persist(patient);
            entityManager.persist(department);
            entityManager.persist(ward);
            entityManager.persist(bed);
            entityManager.persist(otherBed);
            entityManager.flush();

            Encounter encounter = encounterService.admitPatient(patient.getId(), "ENC-" + suffix, admittedAt);
            return new DischargeData(
                    patient.getId(), encounter.getId(), department.getId(), ward.getId(),
                    bed.getId(), otherBed.getId(), admittedAt.plusHours(1), admittedAt.plusDays(2)
            );
        });
        if (data == null) {
            throw new IllegalStateException("Discharge test data was not created");
        }
        encounterService.admitToDepartment(
                data.encounterId(), data.departmentId(), data.wardId(),
                withBed ? data.bedId() : null, data.startedAt()
        );
        return data;
    }

    private record DischargeData(
            UUID patientId,
            UUID encounterId,
            UUID departmentId,
            UUID wardId,
            UUID bedId,
            UUID otherBedId,
            OffsetDateTime startedAt,
            OffsetDateTime dischargedAt
    ) {
    }
}
