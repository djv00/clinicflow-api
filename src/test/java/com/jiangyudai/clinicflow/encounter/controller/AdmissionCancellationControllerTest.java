package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.exception.EncounterLocationHistoryExistsException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterNotFoundException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidAdmissionCancellationException;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EncounterController.class)
@Import(SecurityConfiguration.class)
@WithMockUser(username = "test-operator", roles = "OPERATOR")
class AdmissionCancellationControllerTest {

    private static final UUID ENCOUNTER_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
    );
    private static final String REQUEST = """
            {"cancelledAt": "2025-09-03T10:00:00-04:00"}
            """;

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private EncounterService encounterService;

    @Test
    void requiresCancellationTime() throws Exception {
        mockMvc.perform(post("/api/v1/encounters/{id}/admission-cancellations", ENCOUNTER_ID).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cancelledAt").value("Cancellation time is required"));

        verifyNoInteractions(encounterService);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestBeforeCallingService(String request, String field) throws Exception {
        mockMvc.perform(post("/api/v1/encounters/{id}/admission-cancellations", ENCOUNTER_ID).with(csrf())
                        .contentType("application/json").content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + field).exists());

        verifyNoInteractions(encounterService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-date", "2025-09-03T10:00:00"})
    void requiresAValidTimestampWithOffset(String time) throws Exception {
        mockMvc.perform(post("/api/v1/encounters/{id}/admission-cancellations", ENCOUNTER_ID).with(csrf())
                        .contentType("application/json")
                        .content("{\"cancelledAt\": \"" + time + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(encounterService);
    }

    @ParameterizedTest
    @MethodSource("businessErrors")
    void mapsBusinessErrors(RuntimeException exception, int code, String title) throws Exception {
        when(encounterService.cancelAdmission(eq(ENCOUNTER_ID), any(OffsetDateTime.class), eq("test-operator")))
                .thenThrow(exception);

        mockMvc.perform(post("/api/v1/encounters/{id}/admission-cancellations", ENCOUNTER_ID).with(csrf())
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().is(code))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.detail").value(exception.getMessage()));
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("{\"cancelledAt\": null}", "cancelledAt"),
                Arguments.of(REQUEST.replace("2025-09-03T10:00:00-04:00", OffsetDateTime.now().plusDays(1).toString()), "cancelledAt")
        );
    }

    private static Stream<Arguments> businessErrors() {
        return Stream.of(
                Arguments.of(new EncounterNotFoundException(ENCOUNTER_ID), 404, "Encounter not found"),
                Arguments.of(new EncounterLocationHistoryExistsException(ENCOUNTER_ID), 409, "Encounter location conflict"),
                Arguments.of(new InvalidAdmissionCancellationException("Cancellation time cannot be before hospital admission"),
                        400, "Invalid admission cancellation")
        );
    }
}
