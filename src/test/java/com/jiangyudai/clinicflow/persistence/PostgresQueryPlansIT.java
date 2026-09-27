package com.jiangyudai.clinicflow.persistence;

import com.jiangyudai.clinicflow.encounter.dto.InpatientSearchRequest;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.encounter.service.InpatientQueryService;
import com.jiangyudai.clinicflow.physician.service.PhysicianService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres")
@Import(PostgresQueryPlansIT.CaptureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresQueryPlansIT {
    private static final String SCHEMA = "clinicflow_queries_" + UUID.randomUUID().toString().replace("-", "");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EncounterService encounters;
    @Autowired
    private InpatientQueryService inpatients;
    @Autowired
    private PhysicianService physicians;

    private UUID patient;
    private UUID department;
    private UUID ward;

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureConfiguration {
        @Bean
        static QueryCapture queryCapture() {
            return new QueryCapture();
        }
    }

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
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        // Fail instead of silently paginating a collection fetch in memory.
        properties.add("spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch", () -> true);
    }

    @BeforeAll
    void seedMeasuredDataset() {
        new ResourceDatabasePopulator(new ClassPathResource("query-plans/fixture.sql")).execute(jdbc.getDataSource());
        patient = jdbc.queryForObject("SELECT id FROM patients WHERE medical_record_number = 'QUERY-P-000001'", UUID.class);
        department = jdbc.queryForObject("SELECT id FROM departments WHERE department_code = 'QUERY-D-1'", UUID.class);
        ward = jdbc.queryForObject("SELECT id FROM wards WHERE ward_code = 'QUERY-W-1'", UUID.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM encounters", Integer.class)).isEqualTo(151000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM encounter_locations", Integer.class)).isEqualTo(150800);
    }

    @AfterAll
    void removeGeneratedSchema() {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 20})
    void inpatientPageUsesTwoSelectsRegardlessOfPageSize(int size) {
        var observation = QueryCapture.observe(() -> inpatients.searchInpatients(
                new InpatientSearchRequest(null, null, null, null), 0, size));
        assertThat(observation.queries()).hasSize(2);
        assertThat(observation.result().items()).hasSize(size);
        assertThat(observation.result().totalElements()).isEqualTo(1000);
        assertThat(observation.result().items()).extracting(row -> row.encounterNumber())
                .doesNotHaveDuplicates();
        var next = inpatients.searchInpatients(new InpatientSearchRequest(null, null, null, null), 1, size);
        assertThat(next.items()).extracting(row -> row.id())
                .doesNotContainAnyElementsOf(observation.result().items().stream().map(row -> row.id()).toList());
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 20})
    void patientHistoryUsesThreeSelectsRegardlessOfPageSize(int size) {
        var observation = QueryCapture.observe(() -> encounters.getPatientEncounters(patient, 0, size));
        // Patient existence lookup, page content, and count; proxy IDs do not load each patient again.
        assertThat(observation.queries()).hasSize(3);
        assertThat(observation.result().items()).hasSize(size);
        assertThat(observation.result().totalElements()).isEqualTo(31);
        assertThat(observation.result().items()).allSatisfy(row -> assertThat(row.patientId()).isEqualTo(patient));
        assertThat(observation.result().items().getFirst().encounterNumber()).isEqualTo("QUERY-A-000001");
        var next = encounters.getPatientEncounters(patient, 1, size);
        assertThat(next.items()).extracting(row -> row.id())
                .doesNotContainAnyElementsOf(observation.result().items().stream().map(row -> row.id()).toList());
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 20})
    void physicianPageFetchesAllAffiliationsInThreeSelects(int size) {
        var observation = QueryCapture.observe(() -> physicians.search(null, null, null, 0, size));
        assertThat(observation.queries()).hasSize(3);
        assertThat(observation.result().items()).hasSize(size);
        assertThat(observation.result().totalElements()).isEqualTo(1000);
        assertThat(observation.result().items()).allSatisfy(row -> assertThat(row.departments()).hasSize(2));
        assertThat(observation.result().items()).extracting(row -> row.physicianCode()).isSorted().doesNotHaveDuplicates();
        assertThat(observation.queries().getFirst().sql()).contains("fetch first");
        assertThat(observation.queries().getLast().sql()).doesNotContain("fetch first");
    }

    @Test
    void filtersMatchCurrentLocationAndKeepUnassignedPatientsVisible() {
        var combined = inpatients.searchInpatients(new InpatientSearchRequest(null, null, department, ward), 0, 20);
        assertThat(combined.totalElements()).isEqualTo(5);
        assertThat(combined.items()).allSatisfy(row -> {
            assertThat(row.departmentId()).isEqualTo(department);
            assertThat(row.wardId()).isEqualTo(ward);
        });
        var waiting = inpatients.searchInpatients(new InpatientSearchRequest(null, EncounterStatus.ADMITTED, null, null), 0, 20);
        assertThat(waiting.totalElements()).isEqualTo(200);
        assertThat(waiting.items()).allSatisfy(row -> assertThat(row.departmentId()).isNull());
        var unassigned = inpatients.searchInpatients(new InpatientSearchRequest("QUERY-A-000007", null, null, null), 0, 20);
        assertThat(unassigned.items()).singleElement().satisfies(row -> assertThat(row.bedId()).isNull());
        assertThat(physicians.search(null, department, true, 0, 20).items())
                .allSatisfy(row -> assertThat(row.departments()).hasSize(2));
    }

    @Test
    void recordsPlansForRealServiceQueries() throws Exception {
        writePlans("current");
    }

    private void writePlans(String label) throws Exception {
        var cases = new LinkedHashMap<String, Supplier<?>>();
        cases.put("history-first", () -> encounters.getPatientEncounters(patient, 0, 20));
        cases.put("history-next", () -> encounters.getPatientEncounters(patient, 1, 20));
        cases.put("inpatients-all", () -> inpatients.searchInpatients(new InpatientSearchRequest(null, null, null, null), 0, 20));
        cases.put("inpatients-department", () -> inpatients.searchInpatients(new InpatientSearchRequest(null, null, department, null), 0, 20));
        cases.put("inpatients-ward", () -> inpatients.searchInpatients(new InpatientSearchRequest(null, null, null, ward), 0, 20));
        cases.put("inpatients-combined", () -> inpatients.searchInpatients(new InpatientSearchRequest(null, null, department, ward), 0, 20));
        cases.put("inpatients-waiting", () -> inpatients.searchInpatients(new InpatientSearchRequest(null, EncounterStatus.ADMITTED, null, null), 0, 20));
        cases.put("inpatients-keyword", () -> inpatients.searchInpatients(new InpatientSearchRequest("QUERY-A-000007", null, null, null), 0, 20));
        cases.put("physicians-all", () -> physicians.search(null, null, null, 0, 20));
        cases.put("physicians-department", () -> physicians.search(null, department, true, 0, 20));
        var measurements = new LinkedHashMap<String, Object>();
        for (var entry : cases.entrySet()) {
            var observation = QueryCapture.observe(entry.getValue());
            assertThat(observation.queries()).isNotEmpty();
            var plans = new ArrayList<Object>();
            try (var connection = jdbc.getDataSource().getConnection()) {
                connection.setAutoCommit(false);
                try {
                    // These are parameter-specific plans, not a benchmark of generic prepared plans or concurrent load.
                    connection.createStatement().execute("SET LOCAL plan_cache_mode = force_custom_plan");
                    for (var query : observation.queries()) {
                        query.explain(connection); // Warm the same query before recording five samples.
                        var samples = new ArrayList<Object>();
                        for (int i = 0; i < 5; i++) {
                            samples.add(JSON.readTree(query.explain(connection)).get(0));
                        }
                        plans.add(Map.of("sql", query.sql().replace(SCHEMA, "<test_schema>"),
                                "bindings", query.bindings().stream().map(binding -> Map.of(
                                        "setter", binding.method().getName(), "arguments", Arrays.asList(binding.arguments()))).toList(),
                                "samples", samples));
                    }
                } finally {
                    connection.rollback();
                }
            }
            measurements.put(entry.getKey(), Map.of("selectCount", observation.queries().size(), "queries", plans));
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("databaseVersion", jdbc.queryForObject("SELECT version()", String.class));
        report.put("dataset", Map.of("patients", 5000, "encounters", 151000, "locations", 150800,
                "activeEncounters", 1000, "physicians", 1000, "affiliations", 2000));
        report.put("method", "Actual service SELECTs and JDBC bindings; one EXPLAIN warmup and five parameter-specific samples; no time thresholds.");
        report.put("cases", measurements);
        Path output = Path.of("target", "query-plans", label + ".json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required when TEST_DATABASE_URL is set");
        }
        return value;
    }
}
