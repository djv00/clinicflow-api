package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jiangyudai.clinicflow.encounter.exception.BedOccupiedException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:encounter-transfer-it;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class EncounterTransferIntegrationTest {

    @Autowired
    private EncounterService encounterService;

    @Autowired
    private EncounterRepository encounterRepository;

    @Autowired
    private EncounterLocationRepository encounterLocationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void commitsEncounterTransfer() {
        TransferData data = createTransferData();

        EncounterLocation result = encounterService.transferEncounter(
                data.encounterId(),
                data.targetDepartmentId(),
                data.targetWardId(),
                data.targetBedId(),
                data.transferredAt()
        );

        assertThat(result.getId()).isNotNull();

        transactions.executeWithoutResult(status -> {
            Encounter encounter = encounterRepository
                    .findById(data.encounterId())
                    .orElseThrow();

            List<EncounterLocation> history =
                    encounterLocationRepository
                            .findAllByEncounter_IdOrderByStartedAtAsc(
                                    data.encounterId()
                            );

            assertThat(encounter.getStatus())
                    .isEqualTo(EncounterStatus.IN_DEPARTMENT);

            assertThat(history).hasSize(2);

            EncounterLocation previousLocation = history.get(0);
            EncounterLocation currentLocation = history.get(1);

            assertThat(previousLocation.getDepartment().getId())
                    .isEqualTo(data.currentDepartmentId());
            assertThat(previousLocation.getWard().getId())
                    .isEqualTo(data.currentWardId());
            assertThat(previousLocation.getBed().getId())
                    .isEqualTo(data.currentBedId());
            assertThat(previousLocation.getStartedAt())
                    .isEqualTo(data.currentStartedAt());
            assertThat(previousLocation.getEndedAt())
                    .isEqualTo(data.transferredAt());

            assertThat(currentLocation.getId())
                    .isEqualTo(result.getId());
            assertThat(currentLocation.getDepartment().getId())
                    .isEqualTo(data.targetDepartmentId());
            assertThat(currentLocation.getWard().getId())
                    .isEqualTo(data.targetWardId());
            assertThat(currentLocation.getBed().getId())
                    .isEqualTo(data.targetBedId());
            assertThat(currentLocation.getStartedAt())
                    .isEqualTo(data.transferredAt());
            assertThat(currentLocation.getEndedAt()).isNull();

            assertThat(encounterLocationRepository
                    .findByEncounter_IdAndEndedAtIsNull(
                            data.encounterId()
                    ))
                    .contains(currentLocation);

            assertThat(encounterLocationRepository
                    .existsByBed_IdAndEndedAtIsNull(
                            data.currentBedId()
                    ))
                    .isFalse();

            assertThat(encounterLocationRepository
                    .existsByBed_IdAndEndedAtIsNull(
                            data.targetBedId()
                    ))
                    .isTrue();
        });
    }

    @Test
    void rollsBackBothLocationChangesWhenOuterTransactionFails() {
        TransferData data = createTransferData();

        assertThatThrownBy(() ->
                transactions.executeWithoutResult(status -> {
                    encounterService.transferEncounter(
                            data.encounterId(),
                            data.targetDepartmentId(),
                            data.targetWardId(),
                            data.targetBedId(),
                            data.transferredAt()
                    );

                    // Force the update and insert before simulating failure.
                    entityManager.flush();

                    throw new IllegalStateException(
                            "Simulated failure after transfer"
                    );
                })
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated failure after transfer");

        transactions.executeWithoutResult(status -> {
            Encounter encounter = encounterRepository
                    .findById(data.encounterId())
                    .orElseThrow();

            List<EncounterLocation> history =
                    encounterLocationRepository
                            .findAllByEncounter_IdOrderByStartedAtAsc(
                                    data.encounterId()
                            );

            assertThat(encounter.getStatus())
                    .isEqualTo(EncounterStatus.IN_DEPARTMENT);

            assertThat(history).hasSize(1);

            EncounterLocation currentLocation = history.getFirst();

            assertThat(currentLocation.getDepartment().getId())
                    .isEqualTo(data.currentDepartmentId());
            assertThat(currentLocation.getWard().getId())
                    .isEqualTo(data.currentWardId());
            assertThat(currentLocation.getBed().getId())
                    .isEqualTo(data.currentBedId());
            assertThat(currentLocation.getEndedAt()).isNull();

            assertThat(encounterLocationRepository
                    .existsByBed_IdAndEndedAtIsNull(
                            data.currentBedId()
                    ))
                    .isTrue();

            assertThat(encounterLocationRepository
                    .existsByBed_IdAndEndedAtIsNull(
                            data.targetBedId()
                    ))
                    .isFalse();
        });
    }


    @Test
    void rejectsSecondTransferAfterWaitingForTargetBed()
            throws Exception {
        TransferData first = createTransferData();
        TransferData second = createTransferData();

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<EncounterLocation> secondAttempt =
                    transactions.execute(status -> {
                        encounterService.transferEncounter(
                                first.encounterId(),
                                first.targetDepartmentId(),
                                first.targetWardId(),
                                first.targetBedId(),
                                first.transferredAt()
                        );

                        entityManager.flush();

                        int firstSessionId = ((Number) entityManager
                                .createNativeQuery("select session_id()")
                                .getSingleResult())
                                .intValue();

                        Future<EncounterLocation> attempt =
                                executor.submit(() ->
                                        encounterService.transferEncounter(
                                                second.encounterId(),
                                                first.targetDepartmentId(),
                                                first.targetWardId(),
                                                first.targetBedId(),
                                                second.transferredAt()
                                        )
                                );

                        awaitBlockedTransfer(
                                firstSessionId,
                                attempt
                        );

                        return attempt;
                    });

            assertThat(secondAttempt).isNotNull();

            assertThatThrownBy(() ->
                    secondAttempt.get(10, TimeUnit.SECONDS)
            )
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
                        .isEqualTo(EncounterStatus.IN_DEPARTMENT);

                List<EncounterLocation> firstHistory =
                        encounterLocationRepository
                                .findAllByEncounter_IdOrderByStartedAtAsc(
                                        first.encounterId()
                                );

                List<EncounterLocation> secondHistory =
                        encounterLocationRepository
                                .findAllByEncounter_IdOrderByStartedAtAsc(
                                        second.encounterId()
                                );

                assertThat(firstHistory).hasSize(2);
                assertThat(firstHistory.getFirst().getEndedAt())
                        .isEqualTo(first.transferredAt());

                EncounterLocation firstCurrent =
                        firstHistory.getLast();

                assertThat(firstCurrent.getBed().getId())
                        .isEqualTo(first.targetBedId());
                assertThat(firstCurrent.getEndedAt()).isNull();

                assertThat(secondHistory).hasSize(1);

                EncounterLocation secondCurrent =
                        secondHistory.getFirst();

                assertThat(secondCurrent.getBed().getId())
                        .isEqualTo(second.currentBedId());
                assertThat(secondCurrent.getEndedAt()).isNull();

                assertThat(encounterLocationRepository
                        .existsByBed_IdAndEndedAtIsNull(
                                first.targetBedId()
                        ))
                        .isTrue();

                assertThat(encounterLocationRepository
                        .existsByBed_IdAndEndedAtIsNull(
                                second.currentBedId()
                        ))
                        .isTrue();
            });
        } finally {
            executor.shutdownNow();

            assertThat(executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            ))
                    .as("Transfer worker should stop")
                    .isTrue();
        }
    }

    private void awaitBlockedTransfer(
            int firstSessionId,
            Future<?> secondAttempt
    ) {
        long deadline =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

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
                            "Second transfer completed without waiting for the lock"
                    );
                }

                Thread.sleep(20);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new AssertionError(
                    "Interrupted while checking the transfer lock",
                    exception
            );
        } catch (ExecutionException exception) {
            throw new AssertionError(
                    "Second transfer failed before lock waiting was observed",
                    exception.getCause()
            );
        }

        throw new AssertionError(
                "Second transfer was not observed waiting for the lock"
        );
    }

    private TransferData createTransferData() {
        TransferData data = transactions.execute(status -> {
            String suffix = UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 20);

            OffsetDateTime admittedAt = OffsetDateTime.now()
                    .minusDays(2)
                    .truncatedTo(ChronoUnit.SECONDS);

            OffsetDateTime currentStartedAt =
                    admittedAt.plusHours(1);

            OffsetDateTime transferredAt =
                    currentStartedAt.plusDays(1);

            Patient patient = new Patient(
                    "MRN-" + suffix,
                    "Test",
                    "Patient",
                    LocalDate.of(1990, 5, 14)
            );

            Department currentDepartment = new Department(
                    "CUR-DEPT-" + suffix,
                    "Cardiology"
            );

            Ward currentWard = new Ward(
                    "CUR-WARD-" + suffix,
                    "Cardiology Ward"
            );

            Bed currentBed = new Bed("01", currentWard);

            Department targetDepartment = new Department(
                    "TGT-DEPT-" + suffix,
                    "Neurology"
            );

            Ward targetWard = new Ward(
                    "TGT-WARD-" + suffix,
                    "Neurology Ward"
            );

            Bed targetBed = new Bed("02", targetWard);

            Encounter encounter = new Encounter(
                    "ENC-" + suffix,
                    patient,
                    admittedAt
            );

            entityManager.persist(patient);
            entityManager.persist(currentDepartment);
            entityManager.persist(currentWard);
            entityManager.persist(currentBed);
            entityManager.persist(targetDepartment);
            entityManager.persist(targetWard);
            entityManager.persist(targetBed);
            entityManager.persist(encounter);
            entityManager.flush();

            return new TransferData(
                    encounter.getId(),
                    currentDepartment.getId(),
                    currentWard.getId(),
                    currentBed.getId(),
                    targetDepartment.getId(),
                    targetWard.getId(),
                    targetBed.getId(),
                    currentStartedAt,
                    transferredAt
            );
        });

        if (data == null) {
            throw new IllegalStateException(
                    "Transfer test data was not created"
            );
        }

        encounterService.admitToDepartment(
                data.encounterId(),
                data.currentDepartmentId(),
                data.currentWardId(),
                data.currentBedId(),
                data.currentStartedAt()
        );

        return data;
    }

    private record TransferData(
            UUID encounterId,
            UUID currentDepartmentId,
            UUID currentWardId,
            UUID currentBedId,
            UUID targetDepartmentId,
            UUID targetWardId,
            UUID targetBedId,
            OffsetDateTime currentStartedAt,
            OffsetDateTime transferredAt
    ) {
    }
}