package com.jiangyudai.clinicflow.common.time;

import com.jiangyudai.clinicflow.encounter.dto.AdmitPatientRequest;
import com.jiangyudai.clinicflow.encounter.exception.InvalidAdmissionTimeException;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(BusinessClockIntegrationTest.FixedTime.class)
class BusinessClockIntegrationTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2040-01-01T12:00:00Z");

    @Autowired
    private Validator validator;
    @Autowired
    private PatientService patients;
    @Autowired
    private EncounterService encounters;

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
            return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        }
    }
}
