package com.jiangyudai.clinicflow.encounter.entity;

import com.jiangyudai.clinicflow.encounter.exception.EncounterLocationAlreadyEndedException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterLocationTimeException;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncounterLocationTest {

    private static final OffsetDateTime STARTED_AT =
            OffsetDateTime.parse("2026-09-01T15:00:00-04:00");

    @Test
    void endsCurrentLocation() {
        EncounterLocation location = createLocation();
        OffsetDateTime endedAt =
                OffsetDateTime.parse("2026-09-02T10:00:00-04:00");

        location.endAt(endedAt);

        assertThat(location.getEndedAt()).isEqualTo(endedAt);
    }

    @Test
    void rejectsEndTimeBeforeStartTime() {
        EncounterLocation location = createLocation();
        OffsetDateTime endedAt =
                OffsetDateTime.parse("2026-09-01T14:59:59-04:00");

        assertThatThrownBy(() -> location.endAt(endedAt))
                .isInstanceOf(InvalidEncounterLocationTimeException.class)
                .hasMessage(
                        "Encounter location end time cannot be before start time"
                );
    }

    @Test
    void rejectsMissingEndTime() {
        EncounterLocation location = createLocation();

        assertThatThrownBy(() -> location.endAt(null))
                .isInstanceOf(InvalidEncounterLocationTimeException.class)
                .hasMessage("Encounter location end time is required");
    }

    @Test
    void rejectsEndingLocationTwice() {
        EncounterLocation location = createLocation();
        location.endAt(
                OffsetDateTime.parse("2026-09-02T10:00:00-04:00")
        );

        assertThatThrownBy(() -> location.endAt(
                OffsetDateTime.parse("2026-09-03T10:00:00-04:00")
        ))
                .isInstanceOf(
                        EncounterLocationAlreadyEndedException.class
                )
                .hasMessage("Encounter location is already ended");
    }

    private EncounterLocation createLocation() {
        Patient patient = new Patient(
                "MRN-400001",
                "Maya",
                "Chen",
                LocalDate.of(1990, 5, 14)
        );

        Encounter encounter = new Encounter(
                "ENC-2026-000003",
                patient,
                OffsetDateTime.parse("2026-09-01T14:00:00-04:00")
        );

        Department department = new Department(
                "CARD",
                "Cardiology"
        );

        Ward ward = new Ward(
                "CARD-WARD",
                "Cardiology Ward"
        );

        return new EncounterLocation(
                encounter,
                department,
                ward,
                null,
                STARTED_AT
        );
    }
}
