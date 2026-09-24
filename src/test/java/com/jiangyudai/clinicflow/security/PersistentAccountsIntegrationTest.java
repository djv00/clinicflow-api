package com.jiangyudai.clinicflow.security;

import com.jiangyudai.clinicflow.security.entity.AccountRole;
import com.jiangyudai.clinicflow.security.entity.UserAccount;
import com.jiangyudai.clinicflow.security.repository.UserAccountRepository;
import com.jiangyudai.clinicflow.security.service.AccountProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:persistent-accounts;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.security.user.name=Test.Operator", "spring.security.user.password=initial-password",
        "clinicflow.security.viewer.username=Test.Viewer", "clinicflow.security.viewer.password=viewer-password"
})
@ActiveProfiles("postgres")
@AutoConfigureMockMvc
class PersistentAccountsIntegrationTest {

    @Autowired
    private UserAccountRepository accounts;
    @Autowired
    private AccountProvisioningService provisioning;
    @Autowired
    private UserDetailsService users;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearAccounts() {
        accounts.deleteAll();
    }

    @Test
    void storesOnlyHashesAndAuthenticatesWithTheCanonicalUsernameAndStoredRole() throws Exception {
        initialize();
        assertThat(accounts.count()).isEqualTo(2);
        var account = accounts.findByUsernameKey("test.operator").orElseThrow();
        assertThat(account.getPasswordHash()).startsWith("{bcrypt}").doesNotContain("initial-password");
        assertThat(encoder.matches("initial-password", account.getPasswordHash())).isTrue();

        var operator = login("TEST.OPERATOR", "initial-password");
        mvc.perform(get("/api/auth/session").session(operator)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("Test.Operator"))
                .andExpect(jsonPath("$.roles[0]").value("OPERATOR"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(post("/api/v1/patients").session(operator).with(csrf())
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").exists());

        var viewer = login("TEST.VIEWER", "viewer-password");
        mvc.perform(get("/api/v1/patients").session(viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/patients").session(viewer).with(csrf())
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Access denied"));
    }

    @Test
    void repeatedInitializationDoesNotResetCredentialsOrCreateCaseVariants() throws Exception {
        initialize();
        String originalHash = users.loadUserByUsername("test.operator").getPassword();
        provisioning.initialize(operator("TEST.OPERATOR", "replacement-password"), "TEST.VIEWER", "replacement-viewer");
        assertThat(accounts.count()).isEqualTo(2);
        assertThat(users.loadUserByUsername("test.operator").getPassword()).isEqualTo(originalHash);
        login("test.operator", "initial-password");
        login("test.viewer", "viewer-password");
        assertLoginRejected("test.operator", "replacement-password");
        assertLoginRejected("test.viewer", "replacement-viewer");
    }

    @Test
    void existingAccountsRemainAvailableWithoutBootstrapPasswords() throws Exception {
        initialize();
        provisioning.initialize(operator("Test.Operator", null), "Test.Viewer", "");
        assertThat(accounts.count()).isEqualTo(2);
        login("test.operator", "initial-password");
        login("test.viewer", "viewer-password");
    }

    @Test
    void neverCreatesAPersistentOperatorWithAGeneratedDevelopmentPassword() {
        assertThatThrownBy(() -> provisioning.initialize(operator("Test.Operator", null), "viewer", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Set SPRING_SECURITY_USER_PASSWORD to initialize a PostgreSQL operator account");
        assertThat(accounts.count()).isZero();
    }

    @Test
    void doesNotProvisionAnOptionalViewerWithoutAPassword() {
        provisioning.initialize(operator("Test.Operator", "initial-password"), "viewer", " ");
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(accounts.findByUsernameKey("viewer")).isEmpty();
    }

    @Test
    void refusesDuplicateConfiguredUsernamesBeforeCreatingAnyAccounts() {
        assertThatThrownBy(() -> provisioning.initialize(operator("Test.Operator", "initial-password"),
                "TEST.OPERATOR", "viewer-password")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Viewer and operator usernames must be different");
        assertThat(accounts.count()).isZero();
    }

    @Test
    void rejectsRoleChangesAndRollsBackTheOtherAccountInTheSameInitialization() {
        var existing = accounts.saveAndFlush(new UserAccount("existing", encoder.encode("old-password"), AccountRole.OPERATOR));
        assertThatThrownBy(() -> provisioning.initialize(operator("new-operator", "initial-password"),
                "EXISTING", "viewer-password")).isInstanceOf(IllegalStateException.class)
                .hasMessage("Configured account already exists with a different role");
        assertThat(accounts.findByUsernameKey("new-operator")).isEmpty();
        assertThat(accounts.findById(existing.getId()).orElseThrow().getRole()).isEqualTo(AccountRole.OPERATOR);

        accounts.saveAndFlush(new UserAccount("read-only", encoder.encode("old-password"), AccountRole.VIEWER));
        assertThatThrownBy(() -> provisioning.initialize(operator("READ-ONLY", "new-password"), "viewer", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThat(users.loadUserByUsername("read-only").getAuthorities()).extracting("authority")
                .containsExactly("ROLE_VIEWER");
    }

    @Test
    void disabledAccountsRemainDisabledAndCannotSignInAfterInitialization() throws Exception {
        initialize();
        jdbc.update("UPDATE user_accounts SET enabled = false WHERE username_key = ?", "test.operator");
        provisioning.initialize(operator("Test.Operator", "replacement-password"), "Test.Viewer", "viewer-password");
        assertThat(users.loadUserByUsername("Test.Operator").isEnabled()).isFalse();
        assertLoginRejected("Test.Operator", "initial-password");
        assertLoginRejected("Test.Operator", "replacement-password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Test.Operator", "missing-account"})
    void incorrectCredentialsHaveTheSamePublicError(String username) throws Exception {
        initialize();
        assertLoginRejected(username, "wrong-password");
    }

    @Test
    void databaseRejectsCaseInsensitiveDuplicateUsernames() {
        accounts.saveAndFlush(new UserAccount("Operator", encoder.encode("password"), AccountRole.OPERATOR));
        assertThatThrownBy(() -> accounts.saveAndFlush(new UserAccount("OPERATOR", encoder.encode("password"), AccountRole.VIEWER)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(accounts.count()).isEqualTo(1);
    }

    @Test
    void validatesUsernamesBeforeSavingAccounts() {
        assertThatThrownBy(() -> provisioning.initialize(operator(" ", "password"), "viewer", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provisioning.initialize(operator("a".repeat(101), "password"), "viewer", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provisioning.initialize(operator("operator", "password"), " ", "viewer-password"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(accounts.count()).isZero();
    }

    private void initialize() {
        provisioning.initialize(operator("Test.Operator", "initial-password"), "Test.Viewer", "viewer-password");
    }

    @Test
    void administratorPersistsWithoutResettingItsCredentialsOrGrantingClinicalAccess() throws Exception {
        provisioning.initialize(operator("Test.Operator", "initial-password"), "viewer", "", "Test.Admin", "admin-password");
        var account = accounts.findByUsernameKey("test.admin").orElseThrow();
        assertThat(account.getRole()).isEqualTo(AccountRole.ADMIN);
        String originalHash = account.getPasswordHash();
        provisioning.initialize(operator("Test.Operator", null), "viewer", "", "TEST.ADMIN", "replacement-password");
        assertThat(accounts.findByUsernameKey("test.admin").orElseThrow().getPasswordHash()).isEqualTo(originalHash);
        var admin = login("TEST.ADMIN", "admin-password");
        mvc.perform(get("/api/v1/physicians").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/departments").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/patients").session(admin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/patients").session(admin).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        assertLoginRejected("test.admin", "replacement-password");
        jdbc.update("UPDATE user_accounts SET enabled = false WHERE username_key = ?", "test.admin");
        provisioning.initialize(operator("Test.Operator", null), "viewer", "", "Test.Admin", "admin-password");
        assertLoginRejected("test.admin", "admin-password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEST.OPERATOR", "TEST.VIEWER"})
    void rejectsDuplicateAdminConfigurationBeforeCreatingAccounts(String adminUsername) {
        assertThatThrownBy(() -> provisioning.initialize(operator("Test.Operator", "initial-password"),
                "Test.Viewer", "viewer-password", adminUsername, "admin-password"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(accounts.count()).isZero();
    }

    @Test
    void administratorConfigurationCannotElevateAnExistingViewer() {
        accounts.saveAndFlush(new UserAccount("existing", encoder.encode("viewer-password"), AccountRole.VIEWER));
        assertThatThrownBy(() -> provisioning.initialize(operator("new-operator", "password"), "viewer", "",
                "EXISTING", "admin-password")).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.findByUsernameKey("new-operator")).isEmpty();
        assertThat(accounts.findByUsernameKey("existing").orElseThrow().getRole()).isEqualTo(AccountRole.VIEWER);
    }

    private SecurityProperties.User operator(String username, String password) {
        var operator = new SecurityProperties.User();
        operator.setName(username);
        if (password != null) {
            operator.setPassword(password);
        }
        return operator;
    }

    private MockHttpSession login(String username, String password) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    private void assertLoginRejected(String username, String password) throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", username).param("password", password))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Username or password is incorrect."));
    }
}
