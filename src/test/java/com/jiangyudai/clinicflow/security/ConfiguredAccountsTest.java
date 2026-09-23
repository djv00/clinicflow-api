package com.jiangyudai.clinicflow.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredAccountsTest {

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void viewerDoesNotExistUntilItsPasswordIsConfigured() {
        var users = configuredUsers(properties(), configuration.passwordEncoder(), "viewer", "");
        assertThatThrownBy(() -> users.loadUserByUsername("viewer")).isInstanceOf(UsernameNotFoundException.class);
        assertThat(users.loadUserByUsername("operator").getAuthorities()).extracting("authority")
                .containsExactly("ROLE_OPERATOR");
    }

    @Test
    void configuredViewerHasOnlyReadAccessAndAnEncodedPassword() {
        var encoder = configuration.passwordEncoder();
        var users = configuredUsers(properties(), encoder, "viewer", "viewer-password");
        var viewer = users.loadUserByUsername("viewer");
        assertThat(viewer.getAuthorities()).extracting("authority").containsExactly("ROLE_VIEWER");
        assertThat(viewer.getPassword()).startsWith("{bcrypt}").doesNotContain("viewer-password");
        assertThat(encoder.matches("viewer-password", viewer.getPassword())).isTrue();
    }

    @Test
    void refusesToReplaceTheOperatorWithACaseInsensitiveDuplicateUsername() {
        assertThatThrownBy(() -> configuredUsers(properties(), configuration.passwordEncoder(),
                "OPERATOR", "viewer-password")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Viewer and operator usernames must be different");
    }

    @Test
    void configuredViewerRequiresAUsername() {
        assertThatThrownBy(() -> configuredUsers(properties(), configuration.passwordEncoder(),
                " ", "viewer-password")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void operatorUsernameMustFitBusinessAuditRecords() {
        var properties = properties();
        properties.getUser().setName("a".repeat(101));
        assertThatThrownBy(() -> configuredUsers(properties, configuration.passwordEncoder(), "viewer", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Operator username must not exceed 100 characters for business audit records");
    }

    @Test
    void operatorUsernameCanUseTheFullAuditFieldLength() {
        var properties = properties();
        String username = "a".repeat(100);
        properties.getUser().setName(username);
        var users = configuredUsers(properties, configuration.passwordEncoder(), "viewer", "");
        assertThat(users.loadUserByUsername(username).getUsername()).isEqualTo(username);
    }

    @Test
    void operatorUsernameCannotBeBlank() {
        var properties = properties();
        properties.getUser().setName(" ");
        assertThatThrownBy(() -> configuredUsers(properties, configuration.passwordEncoder(), "viewer", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Operator username is required");
    }

    private SecurityProperties properties() {
        var properties = new SecurityProperties();
        properties.getUser().setName("operator");
        properties.getUser().setPassword("operator-password");
        return properties;
    }

    @Test
    void administratorIsOptionalAndHasNoClinicalRole() {
        var encoder = configuration.passwordEncoder();
        var withoutAdmin = configuredUsers(properties(), encoder, "viewer", "");
        assertThatThrownBy(() -> withoutAdmin.loadUserByUsername("admin")).isInstanceOf(UsernameNotFoundException.class);
        var users = configuration.userDetailsService(properties(), encoder, "viewer", "", "admin", "admin-password");
        var admin = users.loadUserByUsername("admin");
        assertThat(admin.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(encoder.matches("admin-password", admin.getPassword())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPERATOR", "VIEWER"})
    void administratorCannotReplaceAnotherConfiguredAccount(String username) {
        assertThatThrownBy(() -> configuration.userDetailsService(properties(), configuration.passwordEncoder(),
                "viewer", "viewer-password", username, "admin-password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Administrator username must differ from other configured accounts");
    }

    private UserDetailsService configuredUsers(SecurityProperties properties, PasswordEncoder encoder,
                                               String viewerUsername, String viewerPassword) {
        return configuration.userDetailsService(properties, encoder, viewerUsername, viewerPassword, "admin", "");
    }
}
