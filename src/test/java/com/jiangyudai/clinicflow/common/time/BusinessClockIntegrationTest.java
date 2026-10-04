package com.jiangyudai.clinicflow.common.time;

import com.jiangyudai.clinicflow.encounter.dto.AdmitPatientRequest;
import com.jiangyudai.clinicflow.encounter.exception.InvalidAdmissionTimeException;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "OPERATOR")
@Import(BusinessClockIntegrationTest.FixedTime.class)
class BusinessClockIntegrationTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2040-01-01T04:00:00Z");

    @Autowired
    private Validator validator;
    @Autowired
    private PatientService patients;
    @Autowired
    private EncounterService encounters;
    @Autowired
    private EncounterRepository encounterRepository;
    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @CsvSource({"2039-12-31, 201", "2040-01-01, 400"})
    void birthDateUsesTheBusinessDayInsteadOfTheUtcDay(String birthDate, int expectedStatus) throws Exception {
        var result = mockMvc.perform(post("/api/v1/patients").with(csrf()).contentType("application/json")
                        .content("""
                                {"medicalRecordNumber":"%s", "firstName":"Newborn", "lastName":"Test",
                                 "dateOfBirth":"%s"}
                                """.formatted("CLK-" + UUID.randomUUID(), birthDate)))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 400) {
            result.andExpect(jsonPath("$.errors.dateOfBirth").value("Date of birth cannot be in the future"));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "2039-12-31T00:00:00Z, 400",
            "2039-12-31T04:59:59Z, 400",
            "2039-12-31T05:00:00Z, 201",
            "2039-12-30T23:00:00-06:00, 201"
    })
    void admissionCannotPrecedeBirthInTheBusinessTimeZone(String admittedAt, int expectedStatus) throws Exception {
        var patient = patients.createPatient("CLK-" + UUID.randomUUID(), "Newborn", "Test", LocalDate.of(2039, 12, 31));
        String number = "CLK-" + UUID.randomUUID();
        var result = mockMvc.perform(post("/api/v1/encounters").with(csrf()).contentType("application/json")
                        .content("""
                                {"patientId":"%s", "encounterNumber":"%s", "admittedAt":"%s"}
                                """.formatted(patient.id(), number, admittedAt)))
                .andExpect(status().is(expectedStatus));
        assertThat(encounterRepository.existsByEncounterNumber(number)).isEqualTo(expectedStatus == 201);
        if (expectedStatus == 400) {
            result.andExpect(jsonPath("$.code").value("INVALID_ADMISSION_TIME"))
                    .andExpect(jsonPath("$.detail").value("Admission date cannot be before the patient's date of birth"));
        }
    }

    @Test
    void requestAndWorkflowAcceptTheSameInstantInAnotherOffset() {
        var patient = patients.createPatient("CLK-" + UUID.randomUUID(), "Time", "Test", LocalDate.of(1990, 1, 1));
        var time = NOW.withOffsetSameInstant(ZoneOffset.ofHours(-5));
        var request = new AdmitPatientRequest(patient.id(), "CLK-" + UUID.randomUUID(), time);

        assertThat(validator.validate(request)).isEmpty();
        var encounter = encounters.admitPatient(request.patientId(), request.encounterNumber(), request.admittedAt());
        assertThat(encounter.admittedAt().isEqual(NOW)).isTrue();
        // Cancellation exercises an entity rule using the same injected workflow clock.
        assertThat(encounters.cancelAdmission(encounter.id(), time, "test-operator").admissionCancelledAt().isEqual(NOW))
                .isTrue();
    }

    @Test
    void requestAndWorkflowRejectOneSecondAfterTheReferenceTime() {
        var request = new AdmitPatientRequest(UUID.randomUUID(), "CLK-FUTURE", NOW.plusSeconds(1));
        assertThat(validator.validate(request)).singleElement()
                .satisfies(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("admittedAt"));
        assertThatThrownBy(() -> encounters.admitPatient(request.patientId(), request.encounterNumber(), request.admittedAt()))
                .isInstanceOf(InvalidAdmissionTimeException.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW.toInstant(), ZoneId.of("America/Toronto"));
        }
    }
}
