package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.exception.DischargeRecordConflictException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterNotFoundException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidDischargeCancellationException;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EncounterController.class)
class DischargeCancellationControllerTest {

    private static final UUID ENCOUNTER_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
    );
    private static final String REQUEST = """
            {"cancelledAt": "2025-09-03T10:00:00-04:00", "cancelledBy": "demo-clerk"}
            """;

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private EncounterService encounterService;

    @Test
    void requiresTimeAndOperator() throws Exception {
        mockMvc.perform(post("/api/v1/encounters/{id}/discharge-cancellations", ENCOUNTER_ID)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cancelledAt").value("Cancellation time is required"))
                .andExpect(jsonPath("$.errors.cancelledBy").value("Cancellation operator is required"));

        verifyNoInteractions(encounterService);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestBeforeCallingService(String request, String field) throws Exception {
        mockMvc.perform(post("/api/v1/encounters/{id}/discharge-cancellations", ENCOUNTER_ID)
                        .contentType("application/json").content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + field).exists());

        verifyNoInteractions(encounterService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-date", "2025-09-03T10:00:00"})
    void requiresAValidTimestampWithOffset(String time) throws Exception {
        mockMvc.perform(post("/api/v1/encounters/{id}/discharge-cancellations", ENCOUNTER_ID)
                        .contentType("application/json")
                        .content("{\"cancelledAt\": \"" + time + "\", \"cancelledBy\": \"demo-clerk\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(encounterService);
    }

    @ParameterizedTest
    @MethodSource("businessErrors")
    void mapsBusinessErrors(RuntimeException exception, int code, String title) throws Exception {
        when(encounterService.cancelDischarge(eq(ENCOUNTER_ID), any(OffsetDateTime.class), eq("demo-clerk")))
                .thenThrow(exception);

        mockMvc.perform(post("/api/v1/encounters/{id}/discharge-cancellations", ENCOUNTER_ID)
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().is(code))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.detail").value(exception.getMessage()));
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("{\"cancelledAt\": null, \"cancelledBy\": \"demo-clerk\"}", "cancelledAt"),
                Arguments.of(REQUEST.replace("2025-09-03T10:00:00-04:00", OffsetDateTime.now().plusDays(1).toString()), "cancelledAt"),
                Arguments.of(REQUEST.replace("\"demo-clerk\"", "null"), "cancelledBy"),
                Arguments.of(REQUEST.replace("demo-clerk", "   "), "cancelledBy"),
                Arguments.of(REQUEST.replace("demo-clerk", "a".repeat(101)), "cancelledBy")
        );
    }

    private static Stream<Arguments> businessErrors() {
        return Stream.of(
                Arguments.of(new EncounterNotFoundException(ENCOUNTER_ID), 404, "Encounter not found"),
                Arguments.of(new DischargeRecordConflictException("Current discharge record is missing"), 409, "Encounter location conflict"),
                Arguments.of(new InvalidDischargeCancellationException("Cancellation time cannot be before discharge"),
                        400, "Invalid discharge cancellation")
        );
    }
}
