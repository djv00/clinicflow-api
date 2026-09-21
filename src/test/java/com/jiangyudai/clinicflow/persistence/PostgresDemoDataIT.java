package com.jiangyudai.clinicflow.persistence;

import com.jiangyudai.clinicflow.ClinicflowApiApplication;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresDemoDataIT {

    private static final UUID SECOND_BED = UUID.fromString("30000000-0000-0000-0000-000000000002");

    @Test
    void preservesExistingDictionaryAndPatientHistoryWhenDemoInitializationRunsAgain() throws Exception {
        try (var database = new TestDatabase()) {
            UUID departmentId = UUID.randomUUID();
            UUID wardId = UUID.randomUUID();
            UUID bedId = UUID.randomUUID();
            try (var disabled = database.start(false)) {
                assertThat(disabled.containsBean("initializeDemoLocations")).isFalse();
                assertThat(database.count("departments")).isZero();
                assertThat(database.count("wards")).isZero();
                assertThat(database.count("beds")).isZero();
                database.jdbc.update("INSERT INTO departments VALUES (?, 'DEMO-MED', 'Existing Medicine', true)", departmentId);
                database.jdbc.update("INSERT INTO wards VALUES (?, 'DEMO-WARD-1', 'Existing Ward', true)", wardId);
                database.jdbc.update("INSERT INTO beds VALUES (?, '01', ?, true)", bedId, wardId);
            }

            UUID patientId;
            UUID encounterId;
            UUID cancelledId;
            try (var first = database.start(true)) {
                assertDictionaryCounts(database);
                assertThat(database.jdbc.queryForObject("SELECT ward_id FROM beds WHERE id = ?", UUID.class,
                        UUID.fromString("30000000-0000-0000-0000-000000000003"))).isEqualTo(wardId);
                var patients = first.getBean(PatientService.class);
                var encounters = first.getBean(EncounterService.class);
                patientId = patients.createPatient("DEMO-RESTART-TEST", "Fictional", "Patient", LocalDate.of(1990, 5, 14)).getId();
                var start = OffsetDateTime.parse("2025-09-01T09:00:00-04:00");
                cancelledId = encounters.admitPatient(patientId, "DEMO-CANCEL-TEST", start).getId();
                encounters.cancelAdmission(cancelledId, start.plusMinutes(1), "test-operator");
                encounterId = encounters.admitPatient(patientId, "DEMO-STAY-TEST", start.plusHours(1)).getId();
                encounters.admitToDepartment(encounterId, departmentId, wardId, bedId, start.plusHours(2));
                encounters.transferEncounter(encounterId, UUID.fromString("10000000-0000-0000-0000-000000000002"),
                        UUID.fromString("20000000-0000-0000-0000-000000000002"), SECOND_BED, start.plusHours(3));
                encounters.dischargeEncounter(encounterId, start.plusHours(4));
                encounters.cancelDischarge(encounterId, start.plusHours(5), "test-operator");

                database.jdbc.update("UPDATE departments SET active = false WHERE id = ?", departmentId);
                database.jdbc.update("UPDATE wards SET active = false WHERE id = ?", wardId);
                database.jdbc.update("UPDATE beds SET active = false WHERE id = ?", bedId);
            }

            try (var restarted = database.start(true)) {
                assertDictionaryCounts(database);
                assertThat(database.jdbc.queryForObject("SELECT id FROM departments WHERE department_code = 'DEMO-MED'", UUID.class))
                        .isEqualTo(departmentId);
                assertThat(database.jdbc.queryForObject("SELECT department_name FROM departments WHERE id = ?", String.class, departmentId))
                        .isEqualTo("Existing Medicine");
                assertThat(database.jdbc.queryForObject("SELECT ward_name FROM wards WHERE id = ?", String.class, wardId))
                        .isEqualTo("Existing Ward");
                assertThat(database.jdbc.queryForObject("SELECT active FROM departments WHERE id = ?", Boolean.class, departmentId)).isFalse();
                assertThat(database.jdbc.queryForObject("SELECT active FROM wards WHERE id = ?", Boolean.class, wardId)).isFalse();
                assertThat(database.jdbc.queryForObject("SELECT active FROM beds WHERE id = ?", Boolean.class, bedId)).isFalse();
                assertThat(database.count("patients")).isEqualTo(1);
                assertThat(database.count("encounters")).isEqualTo(2);
                var encounters = restarted.getBean(EncounterService.class);
                assertThat(encounters.getEncounter(cancelledId).getStatus()).isEqualTo(EncounterStatus.ADMISSION_CANCELLED);
                var timeline = encounters.getTimeline(encounterId);
                assertThat(timeline.encounter().status()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
                assertThat(timeline.locations()).hasSize(3);
                assertThat(timeline.discharges()).hasSize(1);
                assertThat(database.jdbc.queryForObject("SELECT count(*) FROM encounter_locations WHERE bed_id = ? AND ended_at IS NULL",
                        Integer.class, SECOND_BED)).isEqualTo(1);
                assertThat(database.jdbc.queryForObject("SELECT cancelled_by FROM encounter_discharges WHERE encounter_id = ?",
                        String.class, encounterId)).isEqualTo("test-operator");
                assertThat(restarted.getBean(PatientService.class).getPatient(patientId).getMedicalRecordNumber()).isEqualTo("DEMO-RESTART-TEST");
            }
        }
    }

    @Test
    void rollsBackTheWholeDemoDictionaryWhenAFixtureIdConflicts() throws Exception {
        try (var database = new TestDatabase()) {
            UUID existingWard = UUID.randomUUID();
            UUID conflictingBed = UUID.fromString("30000000-0000-0000-0000-000000000001");
            try (var disabled = database.start(false)) {
                database.jdbc.update("INSERT INTO wards VALUES (?, 'EXISTING-WARD', 'Existing Ward', true)", existingWard);
                database.jdbc.update("INSERT INTO beds VALUES (?, '99', ?, true)", conflictingBed, existingWard);
            }
            assertThatThrownBy(() -> {
                try (var unexpected = database.start(true)) {
                    throw new AssertionError("A conflicting fixture ID must prevent startup");
                }
            }).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
            assertThat(database.count("departments")).isZero();
            assertThat(database.count("wards")).isEqualTo(1);
            assertThat(database.count("beds")).isEqualTo(1);
            assertThat(database.jdbc.queryForObject("SELECT ward_id FROM beds WHERE id = ?", UUID.class, conflictingBed)).isEqualTo(existingWard);
            assertThat(database.jdbc.queryForObject("SELECT bed_number FROM beds WHERE id = ?", String.class, conflictingBed)).isEqualTo("99");
        }
    }

    private void assertDictionaryCounts(TestDatabase database) {
        assertThat(database.count("departments")).isEqualTo(2);
        assertThat(database.count("wards")).isEqualTo(2);
        assertThat(database.count("beds")).isEqualTo(3);
    }

    private static class TestDatabase implements AutoCloseable {
        private final String schema = "clinicflow_it_" + UUID.randomUUID().toString().replace("-", "");
        private final String url;
        private final String username;
        private final String password;
        private final String driver;
        private final Connection connection;
        private final JdbcTemplate jdbc;

        TestDatabase() throws Exception {
            String externalUrl = System.getenv("TEST_DATABASE_URL");
            boolean external = externalUrl != null && !externalUrl.isBlank();
            if (external && !externalUrl.startsWith("jdbc:postgresql://")) {
                throw new IllegalArgumentException("TEST_DATABASE_URL must be a PostgreSQL JDBC URL");
            }
            url = external ? externalUrl : "jdbc:tc:postgresql:17:///clinicflow_demo";
            username = external ? requiredEnvironment("TEST_DATABASE_USERNAME") : "test";
            password = external ? requiredEnvironment("TEST_DATABASE_PASSWORD") : "test";
            driver = external ? "org.postgresql.Driver" : "org.testcontainers.jdbc.ContainerDatabaseDriver";
            Class.forName(driver);
            // Retain the database while restarting application pools, including with Testcontainers JDBC.
            connection = DriverManager.getConnection(url, username, password);
            try {
                jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.execute("CREATE SCHEMA \"" + schema + "\"");
                connection.setSchema(schema);
            } catch (Exception failure) {
                connection.close();
                throw failure;
            }
        }

        ConfigurableApplicationContext start(boolean enabled) {
            return new SpringApplicationBuilder(ClinicflowApiApplication.class).web(WebApplicationType.NONE).run(
                    "--spring.profiles.active=postgres", "--clinicflow.demo-data.enabled=" + enabled,
                    "--spring.datasource.url=" + url, "--spring.datasource.username=" + username,
                    "--spring.datasource.password=" + password, "--spring.datasource.driver-class-name=" + driver,
                    "--spring.datasource.hikari.schema=" + schema,
                    "--spring.flyway.default-schema=" + schema, "--spring.flyway.schemas=" + schema,
                    "--spring.jpa.properties.hibernate.default_schema=" + schema);
        }

        int count(String table) {
            // Table names are constants in this test, never supplied by a request.
            return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        }

        @Override
        public void close() throws Exception {
            try {
                jdbc.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            } finally {
                connection.close();
            }
        }

        private static String requiredEnvironment(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required with TEST_DATABASE_URL");
            }
            return value;
        }
    }
}
