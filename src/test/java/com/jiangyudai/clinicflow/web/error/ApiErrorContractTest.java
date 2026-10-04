package com.jiangyudai.clinicflow.web.error;

import com.jiangyudai.clinicflow.encounter.controller.EncounterController;
import com.jiangyudai.clinicflow.encounter.exception.*;
import com.jiangyudai.clinicflow.encounter.service.EncounterQueryService;
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

import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EncounterController.class)
@Import(SecurityConfiguration.class)
@WithMockUser(username = "test-operator", roles = "OPERATOR")
class ApiErrorContractTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private EncounterService encounters;
    @MockitoBean private EncounterQueryService queries;

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"cancelledAt\":\"not-a-date\"}", "{}"})
    void malformedAndInvalidBodiesUseTheRequestCode(String body) throws Exception {
        mvc.perform(post("/api/v1/encounters/{id}/discharge-cancellations", UUID.randomUUID()).with(csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(encounters);
    }

    @Test
    void anonymousApiReadReturnsTheAuthenticationCode() throws Exception {
        mvc.perform(get("/api/v1/encounters/{id}", UUID.randomUUID()).with(anonymous()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(queries);
    }

    static Stream<Arguments> conflicts() {
        UUID id = UUID.randomUUID();
        return Stream.of(
                Arguments.of(new BedHistoryConflictException(id), "BED_HISTORY_CONFLICT"),
                Arguments.of(new EncounterHistoryConflictException(id), "ENCOUNTER_HISTORY_CONFLICT"),
                Arguments.of(new SubsequentEncounterExistsException(id), "SUBSEQUENT_ENCOUNTER_EXISTS"),
                Arguments.of(new BedOccupiedException(id), "BED_OCCUPIED"),
                Arguments.of(new ActiveEncounterExistsException(id), "ACTIVE_ENCOUNTER_EXISTS"),
                Arguments.of(new DischargeRecordConflictException("Reworded explanation"), "ENCOUNTER_LOCATION_CONFLICT")
        );
    }

    @ParameterizedTest
    @MethodSource("conflicts")
    void distinguishesRecoveryActionsWithoutParsingEnglish(RuntimeException exception, String code) throws Exception {
        when(encounters.cancelDischarge(any(), any(), eq("test-operator"), any())).thenThrow(exception);
        mvc.perform(post("/api/v1/encounters/{id}/discharge-cancellations", UUID.randomUUID()).with(csrf())
                        .contentType("application/json")
                        .content("{\"cancelledAt\":\"2025-09-01T12:00:00Z\",\"expectedDischargeId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").value(exception.getMessage()));
    }
}
