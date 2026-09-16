package com.jiangyudai.clinicflow.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:session-security-it;DB_CLOSE_ON_EXIT=FALSE",
        "spring.security.user.name=test-operator",
        "spring.security.user.password=test-password",
        "clinicflow.security.viewer.username=test-viewer",
        "clinicflow.security.viewer.password=viewer-password"
})
@AutoConfigureMockMvc
class SessionSecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper mapper;
    @Autowired
    private UserDetailsService users;
    @Autowired
    private PasswordEncoder encoder;

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html"})
    void redirectsProtectedPagesToSignIn(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isFound())
                .andExpect(redirectedUrl("/login.html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/patients", "/api/v1/inpatients", "/api/v1/departments", "/api/auth/session"})
    void returnsJsonRatherThanALoginPageForAnonymousApiReads(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login.html", "/login.js", "/session.js", "/patients.css", "/actuator/health"})
    void exposesOnlySignInResourcesAndHealthWithoutASession(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void rejectsAnAnonymousWriteEvenWithAValidCsrfToken() throws Exception {
        Csrf csrf = csrf(null);
        mvc.perform(post("/api/v1/patients").session(csrf.session()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(patient()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRequiresCsrfAndDoesNotAuthenticateOnFailure() throws Exception {
        mvc.perform(post("/api/auth/login").param("username", "test-operator").param("password", "test-password"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/session")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"test-operator", "unknown-user"})
    void returnsTheSameErrorForWrongPasswordAndUnknownUser(String username) throws Exception {
        Csrf csrf = csrf(null);
        mvc.perform(post("/api/auth/login").session(csrf.session()).header(csrf.header(), csrf.token())
                        .param("username", username).param("password", "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Username or password is incorrect."));
        mvc.perform(get("/api/auth/session").session(csrf.session())).andExpect(status().isUnauthorized());
    }

    @Test
    void loginRotatesSessionAndCsrfBeforeAllowingAWrite() throws Exception {
        Csrf before = csrf(null);
        String anonymousSessionId = before.session().getId();
        MockHttpSession session = login(before);
        assertThat(session.getId()).isNotEqualTo(anonymousSessionId);
        mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("test-operator"))
                .andExpect(jsonPath("$.roles[0]").value("OPERATOR"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get("/").session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get("/api/v1/patients").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/patients").session(session).header(before.header(), before.token())
                        .contentType(MediaType.APPLICATION_JSON).content(patient()))
                .andExpect(status().isForbidden());
        Csrf after = csrf(session);
        mvc.perform(post("/api/v1/patients").session(session).header(after.header(), after.token())
                        .contentType(MediaType.APPLICATION_JSON).content(patient()))
                .andExpect(status().isCreated());
    }

    @Test
    void authenticatedWritesStillRequireCsrf() throws Exception {
        MockHttpSession session = login(csrf(null));
        mvc.perform(post("/api/v1/patients").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(patient()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/patients").session(session).header("X-CSRF-TOKEN", "invalid")
                        .contentType(MediaType.APPLICATION_JSON).content(patient()))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRequiresPostAndCsrfAndInvalidatesTheSession() throws Exception {
        MockHttpSession session = login(csrf(null));
        mvc.perform(get("/api/auth/logout").session(session)).andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isOk());
        Csrf csrf = csrf(session);
        String previousId = session.getId();
        mvc.perform(post("/api/auth/logout").session(session).header(csrf.header(), csrf.token()))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("JSESSIONID", 0));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/patients").cookie(new Cookie("JSESSIONID", previousId)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/")).andExpect(status().isFound());
    }

    @Test
    void configuredPasswordIsStoredAsABcryptHash() {
        String encoded = users.loadUserByUsername("test-operator").getPassword();
        assertThat(encoded).startsWith("{bcrypt}").doesNotContain("test-password");
        assertThat(encoder.matches("test-password", encoded)).isTrue();
    }

    @Test
    void viewerCanSignInReadAndSignOutButCannotRegisterPatients() throws Exception {
        MockHttpSession session = login(csrf(null), "test-viewer", "viewer-password");
        mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("test-viewer"))
                .andExpect(jsonPath("$.roles[0]").value("VIEWER"));
        mvc.perform(get("/").session(session)).andExpect(status().isOk());
        for (String path : new String[]{"/patients", "/inpatients", "/departments", "/wards", "/beds"}) {
            mvc.perform(get("/api/v1" + path).session(session)).andExpect(status().isOk());
        }
        Csrf token = csrf(session);
        mvc.perform(post("/api/v1/patients").session(session).header(token.header(), token.token())
                        .contentType(MediaType.APPLICATION_JSON).content(patient()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Access denied"));
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session).header(token.header(), token.token()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    private Csrf csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        MvcResult result = mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store"))).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        return new Csrf((MockHttpSession) result.getRequest().getSession(false),
                body.get("headerName").asText(), body.get("token").asText());
    }

    private MockHttpSession login(Csrf csrf) throws Exception {
        return login(csrf, "test-operator", "test-password");
    }

    private MockHttpSession login(Csrf csrf, String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").session(csrf.session())
                        .header(csrf.header(), csrf.token()).param("username", username)
                        .param("password", password))
                .andExpect(status().isNoContent()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String patient() {
        return """
                {"medicalRecordNumber":"AUTH-%s","firstName":"Demo","lastName":"Patient","dateOfBirth":"1990-05-14"}
                """.formatted(UUID.randomUUID().toString().substring(0, 8));
    }

    private record Csrf(MockHttpSession session, String header, String token) { }
}
