package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.exception.CurrentEncounterLocationNotFoundException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterNotFoundException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidDischargeTimeException;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EncounterController.class)
class EncounterDischargeControllerTest {

    private static final UUID ENCOUNTER_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
    );
    private static final OffsetDateTime DISCHARGED_AT =
            OffsetDateTime.parse("2025-09-03T10:00:00-04:00");
    private static final String REQUEST = """
            {"dischargedAt": "2025-09-03T10:00:00-04:00"}
            """;

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private EncounterService encounterService;

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"dischargedAt\": null}"})
    void rejectsMissingTime(String request) throws Exception {
        mockMvc.perform(post("/api/v1/encounters/{id}/discharges", ENCOUNTER_ID)
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dischargedAt").value("Discharge time is required"));

        verifyNoInteractions(encounterService);
    }

    @Test
    void rejectsFutureTime() throws Exception {
        mockMvc.perform(post("/api/v1/encounters/{id}/discharges", ENCOUNTER_ID)
                        .contentType("application/json")
                        .content("""
                                {"dischargedAt": "%s"}
                                """.formatted(OffsetDateTime.now().plusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dischargedAt")
                        .value("Discharge time cannot be in the future"));

        verifyNoInteractions(encounterService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-date", "2025-09-03T10:00:00"})
    void rejectsTimeWithoutAValidOffset(String time) throws Exception {
        mockMvc.perform(post("/api/v1/encounters/{id}/discharges", ENCOUNTER_ID)
                        .contentType("application/json")
                        .content("{\"dischargedAt\": \"" + time + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(encounterService);
    }

    @Test
    void returnsNotFoundForUnknownEncounter() throws Exception {
        when(encounterService.dischargeEncounter(eq(ENCOUNTER_ID), argThat(
                time -> time != null && time.isEqual(DISCHARGED_AT)
        )))
                .thenThrow(new EncounterNotFoundException(ENCOUNTER_ID));

        mockMvc.perform(post("/api/v1/encounters/{id}/discharges", ENCOUNTER_ID)
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Encounter not found"));
    }

    @Test
    void returnsConflictForMissingLocation() throws Exception {
        when(encounterService.dischargeEncounter(eq(ENCOUNTER_ID), argThat(
                time -> time != null && time.isEqual(DISCHARGED_AT)
        )))
                .thenThrow(new CurrentEncounterLocationNotFoundException(ENCOUNTER_ID));

        mockMvc.perform(post("/api/v1/encounters/{id}/discharges", ENCOUNTER_ID)
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Encounter location conflict"));
    }

    @Test
    void returnsBadRequestForInvalidDischargeTime() throws Exception {
        when(encounterService.dischargeEncounter(eq(ENCOUNTER_ID), argThat(
                time -> time != null && time.isEqual(DISCHARGED_AT)
        )))
                .thenThrow(new InvalidDischargeTimeException(
                        "Discharge time cannot be before the current location start time"
                ));

        mockMvc.perform(post("/api/v1/encounters/{id}/discharges", ENCOUNTER_ID)
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid discharge time"))
                .andExpect(jsonPath("$.detail")
                        .value("Discharge time cannot be before the current location start time"));
    }
}
