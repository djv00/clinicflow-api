package com.jiangyudai.clinicflow.persistence;

import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.BedOccupiedException;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.location.repository.BedRepository;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresWorkflowIT {

    private static final String SCHEMA = "clinicflow_it_" + UUID.randomUUID().toString().replace("-", "");
    private static final OffsetDateTime ADMITTED_AT = OffsetDateTime.parse("2025-09-01T09:00:00-04:00");
    private static final OffsetDateTime ENTERED_AT = ADMITTED_AT.plusHours(1);

    @Autowired
    private PatientService patientService;
    @Autowired
    private EncounterService encounterService;
    @Autowired
    private BedRepository bedRepository;
    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate transactions;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        String url = System.getenv("TEST_DATABASE_URL");
        boolean external = url != null && !url.isBlank();
        if (external && !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("TEST_DATABASE_URL must be a PostgreSQL JDBC URL");
        }
        String username = external ? requiredEnvironment("TEST_DATABASE_USERNAME") : "test";
        String password = external ? requiredEnvironment("TEST_DATABASE_PASSWORD") : "test";
        properties.add("spring.datasource.url", () -> external ? url : "jdbc:tc:postgresql:17:///clinicflow");
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> external
                ? "org.postgresql.Driver" : "org.testcontainers.jdbc.ContainerDatabaseDriver");
        // Both startup migrations and application queries use this run's schema.
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.setTimeout(20);
    }

    @AfterAll
    void removeTestSchema() {
        // SCHEMA is generated here, never taken from the supplied database URL.
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void migratesAnEmptyPostgresSchemaAndDoesNotReapplyIt() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        Patient patient = registerPatient();

        assertThat(flyway.migrate().migrationsExecuted).isZero();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
                Integer.class)).isEqualTo(1);
        assertThat(patientService.getPatient(patient.getId()).getMedicalRecordNumber())
                .isEqualTo(patient.getMedicalRecordNumber());
    }

    @Test
    void preservesDischargeHistoryAndRestoresBedOccupancy() {
        Locations locations = createLocations();
        UUID encounterId = admitPatient();
        EncounterLocation initial = enterDepartment(encounterId, locations);
        OffsetDateTime dischargedAt = ENTERED_AT.plusDays(1);

        encounterService.dischargeEncounter(encounterId, dischargedAt);
        assertThat(bedRepository.findForLookup(locations.firstBedId(), null, null, null))
                .singleElement().satisfies(bed -> assertThat(bed.occupied()).isFalse());
        encounterService.cancelDischarge(encounterId, dischargedAt.plusHours(1), "test-clerk");

        var timeline = encounterService.getTimeline(encounterId);
        assertThat(timeline.encounter().status()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
        assertThat(timeline.encounter().dischargedAt()).isNull();
        assertThat(timeline.locations()).hasSize(2);
        assertThat(timeline.locations().getFirst().id()).isEqualTo(initial.getId());
        assertThat(timeline.locations().getFirst().endedAt().toInstant()).isEqualTo(dischargedAt.toInstant());
        var restored = timeline.locations().getLast();
        assertThat(restored.startedAt().toInstant()).isEqualTo(dischargedAt.toInstant());
        assertThat(restored.endedAt()).isNull();
        assertThat(timeline.discharges()).singleElement().satisfies(discharge -> {
            assertThat(discharge.locationId()).isEqualTo(initial.getId());
            assertThat(discharge.restoredLocationId()).isEqualTo(restored.id());
            assertThat(discharge.cancelledBy()).isEqualTo("test-clerk");
            assertThat(discharge.cancelledAt().toInstant()).isEqualTo(dischargedAt.plusHours(1).toInstant());
        });
        assertThat(bedRepository.findForLookup(locations.firstBedId(), null, null, null))
                .singleElement().satisfies(bed -> assertThat(bed.occupied()).isTrue());
    }

    @Test
    void rollsBackFlushedTransferChanges() {
        Locations locations = createLocations();
        UUID encounterId = admitPatient();
        enterDepartment(encounterId, locations);
        var before = encounterService.getTimeline(encounterId);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            transfer(encounterId, locations);
            // Exercise rollback after PostgreSQL has received both history changes.
            entityManager.flush();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM encounter_locations WHERE encounter_id = ?",
                    Integer.class, encounterId)).isEqualTo(2);
            throw new IllegalStateException("Failure after transfer was flushed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Failure after transfer was flushed");

        assertThat(encounterService.getTimeline(encounterId)).isEqualTo(before);
        assertThat(bedRepository.findForLookup(locations.secondBedId(), null, null, null))
                .singleElement().satisfies(bed -> assertThat(bed.occupied()).isFalse());
        transfer(encounterId, locations);
        assertThat(encounterService.getTimeline(encounterId).locations()).hasSize(2);
    }

    @Test
    void waitsForBedLockAndRejectsTheSecondAdmission() throws Exception {
        Locations locations = createLocations();
        UUID firstEncounter = admitPatient();
        UUID secondEncounter = admitPatient();
        var firstFlushed = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var firstBackend = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);

        try {
            Future<EncounterLocation> first = executor.submit(() -> transactions.execute(status -> {
                firstBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                EncounterLocation result = enterDepartment(firstEncounter, locations);
                entityManager.flush();
                firstFlushed.countDown();
                awaitRelease(releaseFirst);
                return result;
            }));
            assertThat(firstFlushed.await(10, TimeUnit.SECONDS)).isTrue();
            Future<EncounterLocation> second = executor.submit(() -> enterDepartment(secondEncounter, locations));

            awaitBlockedBy(second, firstBackend.get());
            releaseFirst.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).getId()).isNotNull();
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BedOccupiedException.class);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM encounter_locations WHERE bed_id = ? AND ended_at IS NULL",
                    Integer.class, locations.firstBedId())).isEqualTo(1);
            assertThat(encounterService.getEncounter(firstEncounter).getStatus()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
            assertThat(encounterService.getEncounter(secondEncounter).getStatus()).isEqualTo(EncounterStatus.ADMITTED);
            assertThat(encounterService.getTimeline(secondEncounter).locations()).isEmpty();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitBlockedBy(Future<?> contender, int blockingPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (contender.isDone()) {
                contender.get();
                throw new AssertionError("Second admission completed before waiting for the bed lock");
            }
            Boolean blocked = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE datname = current_database() AND ? = ANY(pg_blocking_pids(pid))
                    )
                    """, Boolean.class, blockingPid);
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("PostgreSQL did not report the second admission waiting for the bed lock");
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to commit the first admission");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("First admission was interrupted", exception);
        }
    }

    private Patient registerPatient() {
        return patientService.createPatient("PG-" + UUID.randomUUID(), "Test", "Patient", LocalDate.of(1990, 5, 14));
    }

    private UUID admitPatient() {
        return encounterService.admitPatient(registerPatient().getId(), "PG-" + UUID.randomUUID(), ADMITTED_AT).getId();
    }

    private EncounterLocation enterDepartment(UUID encounterId, Locations locations) {
        return encounterService.admitToDepartment(encounterId, locations.departmentId(),
                locations.wardId(), locations.firstBedId(), ENTERED_AT);
    }

    private void transfer(UUID encounterId, Locations locations) {
        encounterService.transferEncounter(encounterId, locations.departmentId(),
                locations.wardId(), locations.secondBedId(), ENTERED_AT.plusHours(1));
    }

    private Locations createLocations() {
        return transactions.execute(status -> {
            String code = UUID.randomUUID().toString().substring(0, 20);
            Department department = new Department("D-" + code, "Test Medicine");
            Ward ward = new Ward("W-" + code, "Test Ward");
            entityManager.persist(department);
            entityManager.persist(ward);
            Bed first = new Bed("01", ward);
            Bed second = new Bed("02", ward);
            entityManager.persist(first);
            entityManager.persist(second);
            return new Locations(department.getId(), ward.getId(), first.getId(), second.getId());
        });
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required when TEST_DATABASE_URL is set");
        }
        return value;
    }

    private record Locations(UUID departmentId, UUID wardId, UUID firstBedId, UUID secondBedId) {
    }
}
