package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.BedOccupiedException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:department-admission-it;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class DepartmentAdmissionIntegrationTest {

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
    void commitsDepartmentAdmission() {
        AdmissionData data = createAdmissionData();

        EncounterLocation result = encounterService.admitToDepartment(
                data.encounterId(),
                data.departmentId(),
                data.wardId(),
                data.bedId(),
                data.startedAt()
        );

        assertThat(result.getId()).isNotNull();

        transactions.executeWithoutResult(status -> {
            Encounter encounter = encounterRepository
                    .findById(data.encounterId())
                    .orElseThrow();

            EncounterLocation location = encounterLocationRepository
                    .findByEncounter_IdAndEndedAtIsNull(data.encounterId())
                    .orElseThrow();

            assertThat(encounter.getStatus())
                    .isEqualTo(EncounterStatus.IN_DEPARTMENT);

            assertThat(location.getId()).isEqualTo(result.getId());
            assertThat(location.getEncounter().getId())
                    .isEqualTo(data.encounterId());
            assertThat(location.getDepartment().getId())
                    .isEqualTo(data.departmentId());
            assertThat(location.getWard().getId())
                    .isEqualTo(data.wardId());
            assertThat(location.getBed().getId())
                    .isEqualTo(data.bedId());
            assertThat(location.getStartedAt())
                    .isEqualTo(data.startedAt());
            assertThat(location.getEndedAt()).isNull();

            assertThat(encounterLocationRepository
                    .findAllByEncounter_IdOrderByStartedAtAsc(
                            data.encounterId()
                    ))
                    .hasSize(1);
        });
    }

    @Test
    void rollsBackAdmissionWhenOuterTransactionFails() {
        AdmissionData data = createAdmissionData();

        assertThatThrownBy(() ->
                transactions.executeWithoutResult(status -> {
                    encounterService.admitToDepartment(
                            data.encounterId(),
                            data.departmentId(),
                            data.wardId(),
                            data.bedId(),
                            data.startedAt()
                    );

                    // 先执行 SQL，再模拟后续操作失败。
                    entityManager.flush();

                    throw new IllegalStateException(
                            "Simulated failure after admission"
                    );
                })
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated failure after admission");

        transactions.executeWithoutResult(status -> {
            Encounter encounter = encounterRepository
                    .findById(data.encounterId())
                    .orElseThrow();

            assertThat(encounter.getStatus())
                    .isEqualTo(EncounterStatus.ADMITTED);

            assertThat(encounterLocationRepository
                    .findAllByEncounter_IdOrderByStartedAtAsc(
                            data.encounterId()
                    ))
                    .isEmpty();

            assertThat(encounterLocationRepository
                    .existsByBed_IdAndEndedAtIsNull(data.bedId()))
                    .isFalse();
        });
    }

    @Test
    void rejectsSecondAdmissionAfterWaitingForOccupiedBed() throws Exception {
        AdmissionData first = createAdmissionData();
        AdmissionData second = createAdmissionData();

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<EncounterLocation> secondAttempt = transactions.execute(status -> {
                encounterService.admitToDepartment(
                        first.encounterId(),
                        first.departmentId(),
                        first.wardId(),
                        first.bedId(),
                        first.startedAt()
                );

                // 第一笔已经执行 SQL，但暂时不提交。
                entityManager.flush();

                int firstSessionId = ((Number) entityManager
                        .createNativeQuery("select session_id()")
                        .getSingleResult())
                        .intValue();

                Future<EncounterLocation> attempt = executor.submit(() ->
                        encounterService.admitToDepartment(
                                second.encounterId(),
                                first.departmentId(),
                                first.wardId(),
                                first.bedId(),
                                second.startedAt()
                        )
                );

                awaitBlockedAdmission(firstSessionId, attempt);

                return attempt;
            });

            // 上面的事务返回后，第一笔才完成提交。
            assertThat(secondAttempt).isNotNull();

            assertThatThrownBy(() -> secondAttempt.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BedOccupiedException.class);

            transactions.executeWithoutResult(status -> {
                Encounter firstEncounter = encounterRepository
                        .findById(first.encounterId())
                        .orElseThrow();

                Encounter secondEncounter = encounterRepository
                        .findById(second.encounterId())
                        .orElseThrow();

                assertThat(firstEncounter.getStatus())
                        .isEqualTo(EncounterStatus.IN_DEPARTMENT);

                assertThat(secondEncounter.getStatus())
                        .isEqualTo(EncounterStatus.ADMITTED);

                var firstLocations = encounterLocationRepository
                        .findAllByEncounter_IdOrderByStartedAtAsc(
                                first.encounterId()
                        );

                assertThat(firstLocations).hasSize(1);
                assertThat(firstLocations.getFirst().getBed().getId())
                        .isEqualTo(first.bedId());
                assertThat(firstLocations.getFirst().getEndedAt())
                        .isNull();

                assertThat(encounterLocationRepository
                        .findAllByEncounter_IdOrderByStartedAtAsc(
                                second.encounterId()
                        ))
                        .isEmpty();
            });
        } finally {
            executor.shutdownNow();

            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .as("Admission worker should stop")
                    .isTrue();
        }
    }

    private void awaitBlockedAdmission(
            int firstSessionId,
            Future<?> secondAttempt
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        try {
            while (System.nanoTime() < deadline) {
                Number waitingSessions = (Number) entityManager
                        .createNativeQuery("""
                            select count(*)
                            from information_schema.sessions
                            where blocker_id = ?1
                            """)
                        .setParameter(1, firstSessionId)
                        .getSingleResult();

                if (waitingSessions.intValue() > 0) {
                    return;
                }

                if (secondAttempt.isDone()) {
                    secondAttempt.get();

                    throw new AssertionError(
                            "Second admission completed without waiting for the lock"
                    );
                }

                Thread.sleep(20);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new AssertionError(
                    "Interrupted while checking the lock",
                    exception
            );
        } catch (ExecutionException exception) {
            throw new AssertionError(
                    "Second admission failed before lock waiting was observed",
                    exception.getCause()
            );
        }

        throw new AssertionError(
                "Second admission was not observed waiting for the lock"
        );
    }


    private AdmissionData createAdmissionData() {
        return transactions.execute(status -> {
            String suffix = UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 20);

            OffsetDateTime admittedAt = OffsetDateTime.now()
                    .minusDays(1)
                    .truncatedTo(ChronoUnit.SECONDS);

            Patient patient = new Patient(
                    "MRN-" + suffix,
                    "Test",
                    "Patient",
                    LocalDate.of(1990, 5, 14)
            );

            Department department = new Department(
                    "DEPT-" + suffix,
                    "Cardiology"
            );

            Ward ward = new Ward(
                    "WARD-" + suffix,
                    "General Inpatient Ward"
            );

            Bed bed = new Bed("01", ward);

            Encounter encounter = new Encounter(
                    "ENC-" + suffix,
                    patient,
                    admittedAt
            );

            entityManager.persist(patient);
            entityManager.persist(department);
            entityManager.persist(ward);
            entityManager.persist(bed);
            entityManager.persist(encounter);
            entityManager.flush();

            return new AdmissionData(
                    encounter.getId(),
                    department.getId(),
                    ward.getId(),
                    bed.getId(),
                    admittedAt.plusHours(1)
            );
        });
    }

    private record AdmissionData(
            UUID encounterId,
            UUID departmentId,
            UUID wardId,
            UUID bedId,
            OffsetDateTime startedAt
    ) {
    }

    @Test
    void admitsToDepartmentThroughApi() throws Exception {
        AdmissionData data = createAdmissionData();

        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/department-admissions",
                        data.encounterId()
                )
                        .contentType("application/json")
                        .content("""
                            {
                              "departmentId": "%s",
                              "wardId": "%s",
                              "bedId": "%s",
                              "startedAt": "%s"
                            }
                            """.formatted(
                                data.departmentId(),
                                data.wardId(),
                                data.bedId(),
                                data.startedAt()
                        )))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.encounterId")
                        .value(data.encounterId().toString()))
                .andExpect(jsonPath("$.departmentId")
                        .value(data.departmentId().toString()))
                .andExpect(jsonPath("$.wardId")
                        .value(data.wardId().toString()))
                .andExpect(jsonPath("$.bedId")
                        .value(data.bedId().toString()));

        transactions.executeWithoutResult(transactionStatus -> {
            Encounter encounter = encounterRepository
                    .findById(data.encounterId())
                    .orElseThrow();

            assertThat(encounter.getStatus())
                    .isEqualTo(EncounterStatus.IN_DEPARTMENT);

            EncounterLocation location = encounterLocationRepository
                    .findByEncounter_IdAndEndedAtIsNull(data.encounterId())
                    .orElseThrow();

            assertThat(location.getBed().getId())
                    .isEqualTo(data.bedId());
        });
    }

    @Test
    void returnsConflictWhenBedIsOccupied() throws Exception {
        AdmissionData first = createAdmissionData();
        AdmissionData second = createAdmissionData();

        encounterService.admitToDepartment(
                first.encounterId(),
                first.departmentId(),
                first.wardId(),
                first.bedId(),
                first.startedAt()
        );

        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/department-admissions",
                        second.encounterId()
                )
                        .contentType("application/json")
                        .content("""
                            {
                              "departmentId": "%s",
                              "wardId": "%s",
                              "bedId": "%s",
                              "startedAt": "%s"
                            }
                            """.formatted(
                                first.departmentId(),
                                first.wardId(),
                                first.bedId(),
                                second.startedAt()
                        )))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value("Department admission conflict"))
                .andExpect(jsonPath("$.detail")
                        .value("Bed is already occupied: " + first.bedId()));

        transactions.executeWithoutResult(transactionStatus -> {
            Encounter encounter = encounterRepository
                    .findById(second.encounterId())
                    .orElseThrow();

            assertThat(encounter.getStatus())
                    .isEqualTo(EncounterStatus.ADMITTED);

            assertThat(encounterLocationRepository
                    .findAllByEncounter_IdOrderByStartedAtAsc(
                            second.encounterId()
                    ))
                    .isEmpty();
        });
    }
}