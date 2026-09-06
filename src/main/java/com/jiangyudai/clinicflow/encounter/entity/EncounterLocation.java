package com.jiangyudai.clinicflow.encounter.entity;

import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.jiangyudai.clinicflow.encounter.exception.EncounterLocationAlreadyEndedException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterLocationTimeException;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "encounter_locations",
        indexes = {
                @Index(
                        name = "idx_encounter_locations_encounter_end",
                        columnList = "encounter_id, ended_at"
                )
        }
)
public class EncounterLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "encounter_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_encounter_locations_encounter"
            )
    )
    private Encounter encounter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "department_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_encounter_locations_department"
            )
    )
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ward_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_encounter_locations_ward"
            )
    )
    private Ward ward;

    // A department admission can be recorded before a bed is assigned.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "bed_id",
            foreignKey = @ForeignKey(
                    name = "fk_encounter_locations_bed"
            )
    )
    private Bed bed;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    // null marks the encounter's current location.
    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    // Required by JPA.
    protected EncounterLocation() {
    }

    public EncounterLocation(
            Encounter encounter,
            Department department,
            Ward ward,
            Bed bed,
            OffsetDateTime startedAt
    ) {
        this.encounter = encounter;
        this.department = department;
        this.ward = ward;
        this.bed = bed;
        this.startedAt = startedAt;
    }

    /**
     * Ends the current location when the encounter moves or leaves care.
     */
    public void endAt(OffsetDateTime endedAt) {
        if (this.endedAt != null) {
            throw new EncounterLocationAlreadyEndedException();
        }

        if (endedAt == null) {
            throw new InvalidEncounterLocationTimeException(
                    "Encounter location end time is required"
            );
        }

        if (endedAt.isBefore(startedAt)) {
            throw new InvalidEncounterLocationTimeException(
                    "Encounter location end time cannot be before start time"
            );
        }

        this.endedAt = endedAt;
    }

    public UUID getId() {
        return id;
    }

    public Encounter getEncounter() {
        return encounter;
    }

    public Department getDepartment() {
        return department;
    }

    public Ward getWard() {
        return ward;
    }

    public Bed getBed() {
        return bed;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }
}
