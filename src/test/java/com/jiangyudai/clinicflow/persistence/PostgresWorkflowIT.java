package com.jiangyudai.clinicflow.persistence;

import com.jiangyudai.clinicflow.encounter.dto.EncounterResponse;
import com.jiangyudai.clinicflow.encounter.dto.InpatientSearchRequest;
import com.jiangyudai.clinicflow.encounter.service.InpatientQueryService;
import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterPhysicianAssignment;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.entity.PhysicianAssignmentEndReason;
import com.jiangyudai.clinicflow.encounter.repository.EncounterPhysicianAssignmentRepository;
import com.jiangyudai.clinicflow.encounter.exception.BedOccupiedException;
import com.jiangyudai.clinicflow.encounter.exception.DischargeRecordConflictException;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.encounter.service.EncounterPhysicianService;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidPhysicianAssignmentException;
import com.jiangyudai.clinicflow.encounter.exception.PhysicianAssignmentConflictException;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.location.repository.BedRepository;
import com.jiangyudai.clinicflow.patient.dto.PatientResponse;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import com.jiangyudai.clinicflow.physician.entity.Physician;
import com.jiangyudai.clinicflow.physician.exception.DuplicatePhysicianCodeException;
import com.jiangyudai.clinicflow.physician.repository.PhysicianRepository;
import com.jiangyudai.clinicflow.physician.service.PhysicianService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private EncounterPhysicianService encounterPhysicianService;
    @Autowired
    private InpatientQueryService inpatientQueryService;
    @Autowired
    private BedRepository bedRepository;
    @Autowired
    private PhysicianRepository physicianRepository;
    @Autowired
    private EncounterPhysicianAssignmentRepository assignmentRepository;
    @Autowired
    private PhysicianService physicianService;
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
    void queriesCurrentInpatientsWithOptionalFiltersAndPagination() {
        String prefix = "LIST-" + UUID.randomUUID().toString().substring(0, 12);
        Patient waitingPatient = registerPatient();
        UUID waiting = encounterService.admitPatient(waitingPatient.getId(), prefix + "-1", ADMITTED_AT).getId();
        UUID placed = encounterService.admitPatient(registerPatient().getId(), prefix + "-2", ADMITTED_AT).getId();
        Locations locations = createLocations();
        enterDepartment(placed, locations);

        var first = inpatientQueryService.searchInpatients(new InpatientSearchRequest(prefix, null, null, null), 0, 1);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.items().getFirst().id()).isEqualTo(waiting);
        assertThat(first.items().getFirst().departmentId()).isNull();
        var filtered = inpatientQueryService.searchInpatients(new InpatientSearchRequest(prefix,
                EncounterStatus.IN_DEPARTMENT, locations.departmentId(), locations.wardId()), 0, 1);
        assertThat(filtered.totalElements()).isEqualTo(1);
        assertThat(filtered.items().getFirst().bedId()).isEqualTo(locations.firstBedId());
        encounterService.dischargeEncounter(placed, ENTERED_AT.plusHours(1), "test-clerk");
        assertThat(inpatientQueryService.searchInpatients(new InpatientSearchRequest(prefix,
                null, locations.departmentId(), locations.wardId()), 0, 1).items()).isEmpty();
        encounterService.cancelDischarge(placed, ENTERED_AT.plusHours(2), "test-clerk");
        assertThat(inpatientQueryService.searchInpatients(new InpatientSearchRequest(prefix,
                null, null, null), 1, 1).items().getFirst().id()).isEqualTo(placed);
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
    void upgradesExistingPatientAndDepartmentDataToPhysicianSchema() {
        String upgradeSchema = "clinicflow_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        UUID patientId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        try {
            Flyway.configure().dataSource(jdbc.getDataSource())
                    .locations("classpath:db/migration/postgresql")
                    .schemas(upgradeSchema).defaultSchema(upgradeSchema).target("2")
                    .load().migrate();
            jdbc.update("INSERT INTO " + upgradeSchema + ".patients VALUES (?, ?, ?, ?, ?)",
                    patientId, "UPGRADE-001", "Maya", "Chen", LocalDate.of(1990, 5, 14));
            jdbc.update("INSERT INTO " + upgradeSchema + ".departments VALUES (?, ?, ?, ?)",
                    departmentId, "MED", "Medicine", true);

            Flyway upgrade = Flyway.configure().dataSource(jdbc.getDataSource())
                    .locations("classpath:db/migration/postgresql")
                    .schemas(upgradeSchema).defaultSchema(upgradeSchema).target("3").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT medical_record_number FROM " + upgradeSchema
                    + ".patients WHERE id = ?", String.class, patientId)).isEqualTo("UPGRADE-001");
            assertThat(jdbc.queryForObject("SELECT department_code FROM " + upgradeSchema
                    + ".departments WHERE id = ?", String.class, departmentId)).isEqualTo("MED");

            UUID physicianId = UUID.randomUUID();
            jdbc.update("INSERT INTO " + upgradeSchema + ".physicians VALUES (?, ?, ?, ?, ?, ?)",
                    physicianId, "PHY-001", "Alex", "Martin", true, 0);
            jdbc.update("INSERT INTO " + upgradeSchema + ".physician_departments VALUES (?, ?)",
                    physicianId, departmentId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + upgradeSchema
                    + ".physician_departments", Integer.class)).isEqualTo(1);
        } finally {
            // Only remove the schema generated by this test, never the configured database.
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + upgradeSchema + "\" CASCADE");
        }
    }

    @Test
    void physicianSchemaRejectsDuplicateCodesAffiliationsAndMissingReferences() {
        Locations locations = createLocations();
        UUID physicianId = createPhysician(locations.departmentId());
        String code = physicianRepository.findById(physicianId).orElseThrow().getPhysicianCode();

        assertThatThrownBy(() -> physicianRepository.saveAndFlush(new Physician(code, "Alex", "Martin")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO physician_departments VALUES (?, ?)",
                physicianId, locations.departmentId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO physician_departments VALUES (?, ?)",
                physicianId, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO physician_departments VALUES (?, ?)",
                UUID.randomUUID(), locations.departmentId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM departments WHERE id = ?", locations.departmentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM physician_departments WHERE physician_id = ?",
                Integer.class, physicianId)).isEqualTo(1);
    }

    @Test
    void rollsBackFlushedPhysicianAffiliationChanges() {
        Locations original = createLocations();
        Locations replacement = createLocations();
        UUID physicianId = createPhysician(original.departmentId());

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            Physician physician = physicianRepository.findById(physicianId).orElseThrow();
            physician.removeDepartment(original.departmentId());
            physician.addDepartment(entityManager.find(Department.class, replacement.departmentId()));
            physician.rename("Mai", "Chen");
            entityManager.flush();
            assertThat(jdbc.queryForList("SELECT department_id FROM physician_departments WHERE physician_id = ?",
                    UUID.class, physicianId)).containsExactly(replacement.departmentId());
            throw new IllegalStateException("Failure after affiliation changes were flushed");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("Failure after affiliation changes were flushed");

        transactions.executeWithoutResult(status -> {
            Physician reloaded = physicianRepository.findById(physicianId).orElseThrow();
            assertThat(reloaded.getFirstName()).isEqualTo("Maya");
            assertThat(reloaded.getDepartments()).extracting(Department::getId)
                    .containsExactly(original.departmentId());
            assertThat(entityManager.find(Department.class, replacement.departmentId())).isNotNull();
        });
    }

    @Test
    void affiliationEditRejectsStalePhysicianUpdateAcrossTransactions() {
        Locations original = createLocations();
        Locations replacement = createLocations();
        UUID physicianId = createPhysician(original.departmentId());
        Physician stale = transactions.execute(status -> {
            Physician physician = physicianRepository.findById(physicianId).orElseThrow();
            physician.getDepartments().size();
            return physician;
        });

        transactions.executeWithoutResult(status -> {
            Physician physician = physicianRepository.findById(physicianId).orElseThrow();
            physician.removeDepartment(original.departmentId());
            physician.addDepartment(entityManager.find(Department.class, replacement.departmentId()));
        });
        stale.rename("Mai", "Chen");
        assertThatThrownBy(() -> physicianRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        transactions.executeWithoutResult(status -> {
            Physician current = physicianRepository.findById(physicianId).orElseThrow();
            assertThat(current.getFirstName()).isEqualTo("Maya");
            assertThat(current.getDepartments()).extracting(Department::getId)
                    .containsExactly(replacement.departmentId());
        });
    }

    @Test
    void searchesPhysicianDirectoryWithPostgresFiltersAndLiteralKeywords() {
        Locations first = createLocations();
        Locations second = createLocations();
        String prefix = "SEARCH-" + UUID.randomUUID().toString().substring(0, 8);
        var literal = physicianService.create(prefix + "%_!", "Maya", "Chen",
                Set.of(first.departmentId(), second.departmentId()));
        var other = physicianService.create(prefix + "B", "Maya", "Chen", Set.of(first.departmentId()));
        physicianService.changeActive(other.id(), false, other.version());

        var page = physicianService.search(prefix, first.departmentId(), null, 0, 1);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(literal.id());
            assertThat(item.departments()).hasSize(2);
        });
        assertThat(physicianService.search(prefix + "%_!", null, true, 0, 20).items())
                .singleElement().satisfies(item -> assertThat(item.id()).isEqualTo(literal.id()));
        assertThat(physicianService.search(prefix, first.departmentId(), false, 0, 20).items())
                .singleElement().satisfies(item -> assertThat(item.id()).isEqualTo(other.id()));
        assertThat(physicianService.search(prefix, first.departmentId(), null, 2, 1).totalElements()).isEqualTo(2);
    }

    @Test
    void concurrentRegistrationOfTheSamePhysicianCodeReturnsABusinessConflict() throws Exception {
        String code = "RACE-" + UUID.randomUUID().toString().substring(0, 16);
        var firstFlushed = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var firstBackend = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transactions.execute(status -> {
                firstBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                var physician = physicianService.create(code, "Maya", "Chen", Set.of());
                firstFlushed.countDown();
                awaitRelease(releaseFirst);
                return physician;
            }));
            assertThat(firstFlushed.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> physicianService.create(code, "Alex", "Martin", Set.of()));
            awaitBlockedBy(second, firstBackend.get());
            releaseFirst.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).physicianCode()).isEqualTo(code);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(DuplicatePhysicianCodeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM physicians WHERE physician_code = ?",
                    Integer.class, code)).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void searchesPatientsWithLiteralKeywordsAndStablePages() {
        String prefix = "SEARCH-" + UUID.randomUUID().toString().substring(0, 12);
        LocalDate dateOfBirth = LocalDate.of(1990, 5, 14);
        Patient second = patientService.createPatient(prefix + "B", "Test", "Zulu", dateOfBirth);
        Patient first = patientService.createPatient(prefix + "%_!\\", prefix, "O'Neil", dateOfBirth);

        var page = patientService.searchPatients(prefix.toLowerCase(Locale.ROOT), 0, 1);
        assertThat(page.items()).extracting(PatientResponse::id).containsExactly(first.getId());
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(patientService.searchPatients(prefix, 1, 1).items())
                .extracting(PatientResponse::id).containsExactly(second.getId());
        assertThat(patientService.searchPatients(prefix, 2, 1).items()).isEmpty();
        assertThat(patientService.searchPatients(prefix + "%_!\\", 0, 20).items())
                .extracting(PatientResponse::id).containsExactly(first.getId());
        assertThat(patientService.searchPatients("  " + prefix + " o'neil  ", 0, 20).items())
                .extracting(PatientResponse::id).containsExactly(first.getId());
    }

    @Test
    void paginatesPatientEncountersWithoutIncludingAnotherPatient() {
        Patient patient = registerPatient();
        String prefix = "HISTORY-" + UUID.randomUUID().toString().substring(0, 12);
        var first = encounterService.admitPatient(patient.getId(), prefix + "-1", ADMITTED_AT);
        encounterService.cancelAdmission(first.getId(), ADMITTED_AT.plusHours(1), "test-clerk");
        var second = encounterService.admitPatient(patient.getId(), prefix + "-2", ADMITTED_AT);
        encounterService.cancelAdmission(second.getId(), ADMITTED_AT.plusHours(1), "test-clerk");
        var third = encounterService.admitPatient(patient.getId(), prefix + "-3", ADMITTED_AT.plusDays(1));
        encounterService.admitPatient(registerPatient().getId(), prefix + "-OTHER", ADMITTED_AT.plusDays(2));

        var page = encounterService.getPatientEncounters(patient.getId(), 0, 2);
        assertThat(page.items()).extracting(EncounterResponse::id).containsExactly(third.getId(), second.getId());
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(encounterService.getPatientEncounters(patient.getId(), 1, 2).items())
                .singleElement().satisfies(item -> {
                    assertThat(item.id()).isEqualTo(first.getId());
                    assertThat(item.status()).isEqualTo(EncounterStatus.ADMISSION_CANCELLED);
                    assertThat(item.admissionCancelledBy()).isEqualTo("test-clerk");
                });
        assertThat(encounterService.getPatientEncounters(patient.getId(), 2, 2).items()).isEmpty();
    }

    @Test
    void preservesDischargeHistoryAndRestoresBedOccupancy() {
        Locations locations = createLocations();
        UUID encounterId = admitPatient();
        EncounterLocation initial = enterDepartment(encounterId, locations);
        OffsetDateTime dischargedAt = ENTERED_AT.plusDays(1);

        encounterService.dischargeEncounter(encounterId, dischargedAt, "test-clerk");
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
    void refusesAStaleDischargeIdAfterASecondDischarge() {
        UUID encounterId = admitPatient();
        enterDepartment(encounterId, createLocations());
        OffsetDateTime time = ENTERED_AT.plusDays(1);
        encounterService.dischargeEncounter(encounterId, time, "test-clerk");
        UUID originalId = encounterService.getTimeline(encounterId).discharges().getFirst().id();
        encounterService.cancelDischarge(encounterId, time.plusHours(1), "first-clerk", originalId);
        encounterService.dischargeEncounter(encounterId, time, "test-clerk");
        var before = encounterService.getTimeline(encounterId);

        assertThatThrownBy(() -> encounterService.cancelDischarge(
                encounterId, time.plusHours(2), "stale-clerk", originalId))
                .isInstanceOf(DischargeRecordConflictException.class);
        assertThat(encounterService.getTimeline(encounterId)).isEqualTo(before);
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

    @Test
    void upgradesExistingDirectoryAndEncountersWithoutInventingPhysicianAssignments() {
        String upgradeSchema = "clinicflow_assignment_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        UUID patientId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID physicianId = UUID.randomUUID();
        try {
            Flyway.configure().dataSource(jdbc.getDataSource())
                    .locations("classpath:db/migration/postgresql")
                    .schemas(upgradeSchema).defaultSchema(upgradeSchema).target("4").load().migrate();
            jdbc.update("INSERT INTO " + upgradeSchema + ".patients VALUES (?, ?, ?, ?, ?)",
                    patientId, "UPGRADE-001", "Test", "Patient", LocalDate.of(1990, 5, 14));
            jdbc.update("INSERT INTO " + upgradeSchema + ".departments VALUES (?, ?, ?, ?)",
                    departmentId, "MED", "Medicine", true);
            jdbc.update("INSERT INTO " + upgradeSchema + ".physicians VALUES (?, ?, ?, ?, ?, ?)",
                    physicianId, "PHY-001", "Maya", "Chen", true, 0);
            jdbc.update("INSERT INTO " + upgradeSchema + ".physician_departments VALUES (?, ?)",
                    physicianId, departmentId);
            jdbc.update("INSERT INTO " + upgradeSchema + ".encounters "
                            + "(id, encounter_number, patient_id, status, admitted_at) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), "ENC-001", patientId, "ADMITTED", ADMITTED_AT);
            jdbc.update("INSERT INTO " + upgradeSchema + ".user_accounts VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), "admin", "admin", "unchanged-migration-test-hash", "ADMIN", true);
            Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
            for (String table : List.of("patients", "departments", "physicians", "physician_departments",
                    "encounters", "user_accounts")) {
                before.put(table, jdbc.queryForList("SELECT * FROM " + upgradeSchema + "." + table));
            }

            Flyway upgrade = Flyway.configure().dataSource(jdbc.getDataSource())
                    .locations("classpath:db/migration/postgresql")
                    .schemas(upgradeSchema).defaultSchema(upgradeSchema).target("5").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            before.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + upgradeSchema + "." + table))
                    .as(table + " survives the upgrade").isEqualTo(rows));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + upgradeSchema
                    + ".encounter_physician_assignments", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + upgradeSchema + "\" CASCADE");
        }
    }

    @Test
    void physicianHandoverPreservesHistoryAndRollsBackBothChangesOnFailure() {
        Locations locations = createLocations();
        UUID encounterId = admitPatient();
        enterDepartment(encounterId, locations);
        UUID originalPhysician = createPhysician(locations.departmentId());
        UUID nextPhysician = createPhysician(locations.departmentId());
        var original = createAssignment(encounterId, originalPhysician, locations.departmentId(), ENTERED_AT);
        UUID anotherEncounter = admitPatient();
        encounterService.admitToDepartment(anotherEncounter, locations.departmentId(), locations.wardId(), null, ENTERED_AT);
        createAssignment(anotherEncounter, originalPhysician, locations.departmentId(), ENTERED_AT);

        assertThatThrownBy(() -> createAssignment(encounterId, nextPhysician, locations.departmentId(), ENTERED_AT))
                .isInstanceOf(DataIntegrityViolationException.class);
        OffsetDateTime handoverAt = ENTERED_AT.plusHours(1);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assignmentRepository.findById(original.getId()).orElseThrow()
                    .endAt(handoverAt, PhysicianAssignmentEndReason.REASSIGNED, "handover-clerk");
            assignmentRepository.flush();
            createAssignment(encounterId, nextPhysician, locations.departmentId(), handoverAt);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM encounter_physician_assignments WHERE encounter_id = ?",
                    Integer.class, encounterId)).isEqualTo(2);
            throw new IllegalStateException("Failure after physician handover was flushed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Failure after physician handover was flushed");

        var retained = assignmentRepository.findByEncounter_IdAndEndedAtIsNull(encounterId).orElseThrow();
        assertThat(retained.getId()).isEqualTo(original.getId());
        assertThat(retained.getVersion()).isEqualTo(original.getVersion());
        assertThat(retained.getEndReason()).isNull();
        assertThat(retained.getEndedBy()).isNull();
        assertThat(assignmentRepository.findAllByEncounter_IdOrderByStartedAtAscIdAsc(encounterId)).hasSize(1);

        var next = transactions.execute(status -> {
            assignmentRepository.findById(original.getId()).orElseThrow()
                    .endAt(handoverAt, PhysicianAssignmentEndReason.REASSIGNED, "handover-clerk");
            assignmentRepository.flush();
            return createAssignment(encounterId, nextPhysician, locations.departmentId(), handoverAt);
        });
        var history = assignmentRepository.findAllByEncounter_IdOrderByStartedAtAscIdAsc(encounterId);
        assertThat(history).extracting(EncounterPhysicianAssignment::getId).containsExactly(original.getId(), next.getId());
        assertThat(history.getFirst().getEndedAt().toInstant()).isEqualTo(handoverAt.toInstant());
        assertThat(history.getFirst().getAssignedBy()).isEqualTo("test-clerk");
        assertThat(history.getFirst().getEndedBy()).isEqualTo("handover-clerk");
        assertThat(assignmentRepository.findByEncounter_IdAndEndedAtIsNull(encounterId).orElseThrow().getPhysician().getId())
                .isEqualTo(nextPhysician);
        assertThat(assignmentRepository.findByEncounter_IdAndEndedAtIsNull(anotherEncounter).orElseThrow().getPhysician().getId())
                .isEqualTo(originalPhysician);
    }

    @Test
    void assignmentSchemaRejectsInvalidClosureAuditTimesAndReferences() {
        Locations locations = createLocations();
        UUID encounterId = admitPatient();
        enterDepartment(encounterId, locations);
        UUID physicianId = createPhysician(locations.departmentId());
        UUID id = createAssignment(encounterId, physicianId, locations.departmentId(), ENTERED_AT).getId();

        // Direct SQL checks the migration even when entity validation is bypassed.
        for (String change : List.of("ended_at = started_at", "end_reason = 'RELEASED'", "ended_by = 'operator'",
                "ended_at = started_at, ended_by = 'operator'",
                "ended_at = started_at, end_reason = 'RELEASED'",
                "ended_at = started_at, end_reason = 'UNKNOWN', ended_by = 'operator'",
                "ended_at = started_at, end_reason = 'RELEASED', ended_by = ' '",
                "ended_at = started_at - INTERVAL '1 second', end_reason = 'RELEASED', ended_by = 'operator'",
                "assigned_by = ' '")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE encounter_physician_assignments SET " + change + " WHERE id = ?", id))
                    .as(change).isInstanceOf(DataIntegrityViolationException.class);
        }
        for (String column : List.of("encounter_id", "physician_id", "department_id")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE encounter_physician_assignments SET " + column + " = ? WHERE id = ?",
                    UUID.randomUUID(), id)).as(column).isInstanceOf(DataIntegrityViolationException.class);
        }
        transactions.executeWithoutResult(status -> assignmentRepository.findById(id).orElseThrow()
                .endAt(ENTERED_AT, PhysicianAssignmentEndReason.RELEASED, "operator"));
        // Remove the separate affiliation so it cannot mask the assignment's physician foreign key.
        jdbc.update("DELETE FROM physician_departments WHERE physician_id = ?", physicianId);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM physicians WHERE id = ?", physicianId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_physician_assignments_physician");
        assertThat(assignmentRepository.findByEncounter_IdAndEndedAtIsNull(encounterId)).isEmpty();
        assertThat(assignmentRepository.findAllByEncounter_IdOrderByStartedAtAscIdAsc(encounterId)).singleElement()
                .satisfies(row -> {
                    assertThat(row.getStartedAt().toInstant()).isEqualTo(ENTERED_AT.toInstant());
                    assertThat(row.getEndedAt().toInstant()).isEqualTo(ENTERED_AT.toInstant());
                    assertThat(row.getAssignedBy()).isEqualTo("test-clerk");
                });
    }

    @Test
    void concurrentOpenPhysicianAssignmentsCannotBothCommit() throws Exception {
        Locations locations = createLocations();
        UUID encounterId = admitPatient();
        enterDepartment(encounterId, locations);
        UUID firstPhysician = createPhysician(locations.departmentId());
        UUID secondPhysician = createPhysician(locations.departmentId());
        var firstFlushed = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var firstBackend = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transactions.execute(status -> {
                firstBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                var assignment = createAssignment(encounterId, firstPhysician, locations.departmentId(), ENTERED_AT);
                firstFlushed.countDown();
                awaitRelease(releaseFirst);
                return assignment;
            }));
            assertThat(firstFlushed.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> createAssignment(encounterId, secondPhysician, locations.departmentId(), ENTERED_AT));
            awaitBlockedBy(second, firstBackend.get());
            releaseFirst.countDown();

            UUID committedId = first.get(10, TimeUnit.SECONDS).getId();
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(DataIntegrityViolationException.class);
            assertThat(assignmentRepository.findAllByEncounter_IdOrderByStartedAtAscIdAsc(encounterId))
                    .extracting(EncounterPhysicianAssignment::getId).containsExactly(committedId);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void competingPhysicianSelectionsReturnAConflictAfterWaitingForTheEncounter() throws Exception {
        Locations locations = createLocations();
        UUID encounterId = admitPatient();
        UUID locationId = enterDepartment(encounterId, locations).getId();
        UUID firstPhysician = createPhysician(locations.departmentId());
        UUID secondPhysician = createPhysician(locations.departmentId());
        var firstFlushed = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var firstBackend = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transactions.execute(status -> {
                firstBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                var assignment = encounterPhysicianService.assign(encounterId, firstPhysician, locationId, null, ENTERED_AT, "first-clerk");
                firstFlushed.countDown();
                awaitRelease(releaseFirst);
                return assignment;
            }));
            assertThat(firstFlushed.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> encounterPhysicianService.assign(encounterId, secondPhysician, locationId, null, ENTERED_AT, "second-clerk"));
            awaitBlockedBy(second, firstBackend.get());
            releaseFirst.countDown();

            UUID committedId = first.get(10, TimeUnit.SECONDS).getId();
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(PhysicianAssignmentConflictException.class);
            assertThat(encounterPhysicianService.getHistory(encounterId)).singleElement()
                    .satisfies(item -> assertThat(item.getId()).isEqualTo(committedId));
            var replacement = encounterPhysicianService.assign(encounterId, secondPhysician, locationId, committedId,
                    ENTERED_AT.plusHours(1), "handover-clerk");
            assertThat(encounterPhysicianService.getHistory(encounterId)).hasSize(2);
            assertThat(assignmentRepository.findByEncounter_IdAndEndedAtIsNull(encounterId).orElseThrow().getId())
                    .isEqualTo(replacement.getId());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void assignmentAndDischargeSerializeInEitherOrder(boolean assignmentFirst) throws Exception {
        Locations locations = createLocations();
        UUID encounterId = admitPatient();
        UUID locationId = enterDepartment(encounterId, locations).getId();
        UUID physicianId = createPhysician(locations.departmentId());
        var firstFlushed = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var firstBackend = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        Runnable assign = () -> encounterPhysicianService.assign(encounterId, physicianId, locationId, null, ENTERED_AT, "assign-clerk");
        Runnable discharge = () -> encounterService.dischargeEncounter(encounterId, ENTERED_AT.plusHours(1), "discharge-clerk");
        try {
            var first = executor.submit(() -> transactions.executeWithoutResult(status -> {
                firstBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                (assignmentFirst ? assign : discharge).run();
                entityManager.flush();
                firstFlushed.countDown();
                awaitRelease(releaseFirst);
            }));
            assertThat(firstFlushed.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(assignmentFirst ? discharge : assign);
            awaitBlockedBy(second, firstBackend.get());
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            if (assignmentFirst) {
                second.get(10, TimeUnit.SECONDS);
                assertThat(encounterPhysicianService.getHistory(encounterId)).singleElement().satisfies(item -> {
                    assertThat(item.getEndReason()).isEqualTo(PhysicianAssignmentEndReason.DISCHARGE);
                    assertThat(item.getEndedBy()).isEqualTo("discharge-clerk");
                    assertThat(item.getEndedAt().toInstant()).isEqualTo(ENTERED_AT.plusHours(1).toInstant());
                });
            } else {
                assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(InvalidEncounterStatusException.class);
                assertThat(encounterPhysicianService.getHistory(encounterId)).isEmpty();
            }
            assertThat(encounterService.getEncounter(encounterId).getStatus()).isEqualTo(EncounterStatus.DISCHARGED);
            assertThat(assignmentRepository.findByEncounter_IdAndEndedAtIsNull(encounterId)).isEmpty();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void assignmentRechecksEligibilityAfterAConcurrentDirectoryEdit(boolean deactivate) throws Exception {
        Locations locations = createLocations();
        UUID encounterId = admitPatient();
        UUID locationId = enterDepartment(encounterId, locations).getId();
        UUID physicianId = createPhysician(locations.departmentId());
        var original = physicianService.get(physicianId);
        var firstFlushed = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var firstBackend = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var edit = executor.submit(() -> transactions.executeWithoutResult(status -> {
                firstBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                if (deactivate) {
                    physicianService.changeActive(physicianId, false, original.version());
                } else {
                    physicianService.update(physicianId, original.firstName(), original.lastName(), Set.of(), original.version());
                }
                firstFlushed.countDown();
                awaitRelease(releaseFirst);
            }));
            assertThat(firstFlushed.await(10, TimeUnit.SECONDS)).isTrue();
            var assign = executor.submit(() -> encounterPhysicianService.assign(encounterId, physicianId, locationId, null, ENTERED_AT, "operator"));
            awaitBlockedBy(assign, firstBackend.get());
            releaseFirst.countDown();
            edit.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> assign.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(InvalidPhysicianAssignmentException.class);
            assertThat(encounterPhysicianService.getHistory(encounterId)).isEmpty();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private EncounterPhysicianAssignment createAssignment(UUID encounterId, UUID physicianId,
                                                          UUID departmentId, OffsetDateTime startedAt) {
        return transactions.execute(status -> assignmentRepository.saveAndFlush(new EncounterPhysicianAssignment(
                entityManager.getReference(Encounter.class, encounterId),
                entityManager.getReference(Physician.class, physicianId),
                entityManager.getReference(Department.class, departmentId), startedAt, "test-clerk")));
    }

    private void awaitBlockedBy(Future<?> contender, int blockingPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (contender.isDone()) {
                contender.get();
                throw new AssertionError("Second operation completed before waiting for the database lock");
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
        throw new AssertionError("PostgreSQL did not report the second operation waiting for the database lock");
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to commit the first operation");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("First operation was interrupted", exception);
        }
    }

    private Patient registerPatient() {
        return patientService.createPatient("PG-" + UUID.randomUUID(), "Test", "Patient", LocalDate.of(1990, 5, 14));
    }

    private UUID createPhysician(UUID departmentId) {
        return transactions.execute(status -> {
            Physician physician = new Physician("PHY-" + UUID.randomUUID().toString().substring(0, 20),
                    "Maya", "Chen");
            physician.addDepartment(entityManager.find(Department.class, departmentId));
            return physicianRepository.saveAndFlush(physician).getId();
        });
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
                locations.wardId(), locations.secondBedId(), ENTERED_AT.plusHours(1), "test-clerk");
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
