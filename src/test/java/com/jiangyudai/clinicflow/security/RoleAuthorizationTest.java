package com.jiangyudai.clinicflow.security;

import com.jiangyudai.clinicflow.encounter.controller.EncounterController;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.patient.controller.PatientController;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({PatientController.class, EncounterController.class})
@Import(SecurityConfiguration.class)
class RoleAuthorizationTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private PatientService patients;
    @MockitoBean
    private EncounterService encounters;

    @ParameterizedTest
    @ValueSource(strings = {"/patients", "/encounters", "/encounters/{id}/department-admissions",
            "/encounters/{id}/transfers", "/encounters/{id}/discharges",
            "/encounters/{id}/admission-cancellations", "/encounters/{id}/discharge-cancellations"})
    void viewerCannotReachAnyBusinessWriteEvenWithValidCsrf(String path) throws Exception {
        mvc.perform(post("/api/v1" + path, "11111111-1111-1111-1111-111111111111")
                        .with(user("viewer").roles("VIEWER")).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Access denied"))
                .andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(patients, encounters);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/patients", "/encounters", "/encounters/{id}/department-admissions",
            "/encounters/{id}/transfers", "/encounters/{id}/discharges",
            "/encounters/{id}/admission-cancellations", "/encounters/{id}/discharge-cancellations"})
    void operatorReachesBusinessValidationForEveryWrite(String path) throws Exception {
        mvc.perform(post("/api/v1" + path, "11111111-1111-1111-1111-111111111111")
                        .with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void viewerCannotUseAnotherWriteMethod(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), "/api/v1/patients")
                        .with(user("viewer").roles("VIEWER")).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(patients, encounters);
    }

    @Test
    void anUnrecognisedRoleCannotReadBusinessData() throws Exception {
        mvc.perform(get("/api/v1/patients").with(user("unassigned").roles("OTHER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Access denied"));
        verifyNoInteractions(patients, encounters);
    }

    @Test
    void csrfErrorsRemainDistinctFromPermissionErrors() throws Exception {
        mvc.perform(post("/api/v1/patients").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Request not allowed"));
        verifyNoInteractions(patients, encounters);
    }
}
