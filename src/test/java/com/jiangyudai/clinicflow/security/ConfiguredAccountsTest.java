package com.jiangyudai.clinicflow.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredAccountsTest {

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void viewerDoesNotExistUntilItsPasswordIsConfigured() {
        var users = configuration.userDetailsService(properties(), configuration.passwordEncoder(), "viewer", "");
        assertThatThrownBy(() -> users.loadUserByUsername("viewer")).isInstanceOf(UsernameNotFoundException.class);
        assertThat(users.loadUserByUsername("operator").getAuthorities()).extracting("authority")
                .containsExactly("ROLE_OPERATOR");
    }

    @Test
    void configuredViewerHasOnlyReadAccessAndAnEncodedPassword() {
        var encoder = configuration.passwordEncoder();
        var users = configuration.userDetailsService(properties(), encoder, "viewer", "viewer-password");
        var viewer = users.loadUserByUsername("viewer");
        assertThat(viewer.getAuthorities()).extracting("authority").containsExactly("ROLE_VIEWER");
        assertThat(viewer.getPassword()).startsWith("{bcrypt}").doesNotContain("viewer-password");
        assertThat(encoder.matches("viewer-password", viewer.getPassword())).isTrue();
    }

    @Test
    void refusesToReplaceTheOperatorWithACaseInsensitiveDuplicateUsername() {
        assertThatThrownBy(() -> configuration.userDetailsService(properties(), configuration.passwordEncoder(),
                "OPERATOR", "viewer-password")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Viewer and operator usernames must be different");
    }

    @Test
    void configuredViewerRequiresAUsername() {
        assertThatThrownBy(() -> configuration.userDetailsService(properties(), configuration.passwordEncoder(),
                " ", "viewer-password")).isInstanceOf(IllegalArgumentException.class);
    }

    private SecurityProperties properties() {
        var properties = new SecurityProperties();
        properties.getUser().setName("operator");
        properties.getUser().setPassword("operator-password");
        return properties;
    }
}
