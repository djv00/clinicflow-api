package com.jiangyudai.clinicflow.persistence;

import com.jiangyudai.clinicflow.ClinicflowApiApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PostgresAccountsIT {

    @Test
    void upgradesAnExistingDatabaseAndPreservesAccountsAcrossApplicationRestarts() throws Exception {
        String externalUrl = System.getenv("TEST_DATABASE_URL");
        boolean external = externalUrl != null && !externalUrl.isBlank();
        if (external && !externalUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("TEST_DATABASE_URL must be a PostgreSQL JDBC URL");
        }
        String url = external ? externalUrl : "jdbc:tc:postgresql:17:///clinicflow_accounts";
        String username = external ? requiredEnvironment("TEST_DATABASE_USERNAME") : "test";
        String password = external ? requiredEnvironment("TEST_DATABASE_PASSWORD") : "test";
        String driver = external ? "org.postgresql.Driver" : "org.testcontainers.jdbc.ContainerDatabaseDriver";
        String schema = "clinicflow_it_" + UUID.randomUUID().toString().replace("-", "");
        Class.forName(driver);

        // Keep the Testcontainers JDBC database alive while application pools are closed.
        try (var connection = DriverManager.getConnection(url, username, password)) {
            try {
                Flyway.configure().dataSource(new SingleConnectionDataSource(connection, true))
                        .locations("classpath:db/migration/postgresql").defaultSchema(schema).schemas(schema)
                        .target("1").load().migrate();
                UUID patientId = UUID.randomUUID();
                try (var insert = connection.prepareStatement("INSERT INTO \"" + schema + "\".patients "
                        + "(id, medical_record_number, first_name, last_name, date_of_birth) VALUES (?, ?, ?, ?, ?)")) {
                    insert.setObject(1, patientId);
                    insert.setString(2, "ACCOUNT-MIGRATION-TEST");
                    insert.setString(3, "Fictional");
                    insert.setString(4, "Patient");
                    insert.setDate(5, java.sql.Date.valueOf("1990-01-01"));
                    insert.executeUpdate();
                }

                String originalHash;
                try (var first = start(url, username, password, driver, schema, "initial-password", "viewer-password")) {
                    var jdbc = first.getBean(JdbcTemplate.class);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success",
                            Integer.class)).isEqualTo(1);
                    assertThat(first.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
                    originalHash = jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE role = 'OPERATOR'", String.class);
                    assertThat(originalHash).startsWith("{bcrypt}");
                    assertThat(first.getBean(PasswordEncoder.class).matches("initial-password", originalHash)).isTrue();
                    var mvc = mvc(first);
                    var session = login(mvc, "PERSISTENT.OPERATOR", "initial-password");
                    mvc.perform(get("/api/v1/patients/{id}", patientId).session(session)).andExpect(status().isOk())
                            .andExpect(jsonPath("$.medicalRecordNumber").value("ACCOUNT-MIGRATION-TEST"));
                    login(mvc, "persistent.viewer", "viewer-password");
                    jdbc.update("UPDATE user_accounts SET enabled = false WHERE role = 'VIEWER'");
                }

                try (var second = start(url, username, password, driver, schema, "replacement-password", "replacement-viewer")) {
                    var jdbc = second.getBean(JdbcTemplate.class);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts", Integer.class)).isEqualTo(2);
                    assertThat(jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE role = 'OPERATOR'", String.class))
                            .isEqualTo(originalHash);
                    var mvc = mvc(second);
                    login(mvc, "persistent.operator", "initial-password");
                    rejectedLogin(mvc, "persistent.operator", "replacement-password");
                    rejectedLogin(mvc, "persistent.viewer", "viewer-password");
                    assertThat(jdbc.queryForObject("SELECT enabled FROM user_accounts WHERE role = 'VIEWER'", Boolean.class)).isFalse();
                }

                try (var third = start(url, username, password, driver, schema, "", "")) {
                    var mvc = mvc(third);
                    var session = login(mvc, "persistent.operator", "initial-password");
                    mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isOk())
                            .andExpect(jsonPath("$.username").value("Persistent.Operator"))
                            .andExpect(jsonPath("$.roles[0]").value("OPERATOR"));
                    mvc.perform(get("/api/v1/patients/{id}", patientId).session(session)).andExpect(status().isOk());
                    assertThat(third.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM user_accounts", Integer.class))
                            .isEqualTo(2);
                }
            } finally {
                try (var statement = connection.createStatement()) {
                    // Only this test's generated schema is removed.
                    statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
                }
            }
        }
    }

    private ConfigurableApplicationContext start(String url, String username, String password, String driver,
            String schema, String operatorPassword, String viewerPassword) {
        return new SpringApplicationBuilder(ClinicflowApiApplication.class).run(
                "--spring.profiles.active=postgres", "--server.port=0",
                "--spring.datasource.url=" + url, "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password, "--spring.datasource.driver-class-name=" + driver,
                "--spring.datasource.hikari.schema=" + schema,
                "--spring.flyway.default-schema=" + schema, "--spring.flyway.schemas=" + schema,
                "--spring.jpa.properties.hibernate.default_schema=" + schema,
                "--spring.security.user.name=Persistent.Operator", "--spring.security.user.password=" + operatorPassword,
                "--clinicflow.security.viewer.username=Persistent.Viewer", "--clinicflow.security.viewer.password=" + viewerPassword);
    }

    private MockMvc mvc(ConfigurableApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).apply(springSecurity()).build();
    }

    private MockHttpSession login(MockMvc mvc, String username, String password) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    private void rejectedLogin(MockMvc mvc, String username, String password) throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", username).param("password", password))
                .andExpect(status().isUnauthorized());
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required with TEST_DATABASE_URL");
        }
        return value;
    }
}
