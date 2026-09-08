package com.jiangyudai.clinicflow.encounter.entity;

import com.jiangyudai.clinicflow.encounter.exception.InvalidDischargeCancellationException;
import com.jiangyudai.clinicflow.encounter.exception.DischargeRecordConflictException;
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
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "encounter_discharges",
        indexes = @Index(name = "idx_discharges_encounter_cancelled", columnList = "encounter_id, cancelled_at"),
        uniqueConstraints = @UniqueConstraint(name = "uk_discharges_location", columnNames = "location_id"))
public class EncounterDischarge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_discharges_encounter"))
    private Encounter encounter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_discharges_location"))
    private EncounterLocation location;

    @Column(name = "discharged_at", nullable = false, updatable = false)
    private OffsetDateTime dischargedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restored_location_id",
            foreignKey = @ForeignKey(name = "fk_discharges_restored_location"))
    private EncounterLocation restoredLocation;

    protected EncounterDischarge() {
    }

    public EncounterDischarge(Encounter encounter, EncounterLocation location) {
        if (encounter.getStatus() != EncounterStatus.DISCHARGED
                || encounter.getDischargedAt() == null
                || location.getEndedAt() == null
                || !location.getEndedAt().isEqual(encounter.getDischargedAt())
                || (location.getEncounter() != encounter
                    && (encounter.getId() == null
                        || !encounter.getId().equals(location.getEncounter().getId())))) {
            throw new DischargeRecordConflictException("Discharge must reference the encounter's closed location");
        }
        this.encounter = encounter;
        this.location = location;
        this.dischargedAt = encounter.getDischargedAt();
    }

    /**
     * Retains discharge audit and links the continuation of effective location history.
     */
    public void cancelAt(OffsetDateTime cancelledAt, String cancelledBy, EncounterLocation restoredLocation) {
        validateCancellation(cancelledAt, cancelledBy);
        if (restoredLocation == null
                || restoredLocation.getEndedAt() != null
                || restoredLocation.getStartedAt() == null
                || !dischargedAt.isEqual(restoredLocation.getStartedAt())
                || (restoredLocation.getEncounter() != encounter
                    && (encounter.getId() == null
                        || !encounter.getId().equals(restoredLocation.getEncounter().getId())))) {
            throw new DischargeRecordConflictException("Restored location must start at discharge for the same encounter");
        }
        this.cancelledAt = cancelledAt;
        this.cancelledBy = cancelledBy;
        this.restoredLocation = restoredLocation;
    }

    /**
     * Validates cancellation before the workflow checks historical availability.
     */
    public void validateCancellation(OffsetDateTime cancelledAt, String cancelledBy) {
        if (this.cancelledAt != null) {
            throw new DischargeRecordConflictException("Discharge has already been cancelled");
        }
        if (cancelledAt == null) {
            throw new InvalidDischargeCancellationException("Cancellation time is required");
        }
        if (cancelledAt.isBefore(dischargedAt)) {
            throw new InvalidDischargeCancellationException("Cancellation time cannot be before discharge");
        }
        if (cancelledAt.isAfter(OffsetDateTime.now())) {
            throw new InvalidDischargeCancellationException("Cancellation time cannot be in the future");
        }
        if (cancelledBy == null || cancelledBy.isBlank()) {
            throw new InvalidDischargeCancellationException("Cancellation operator is required");
        }
        if (cancelledBy.length() > 100) {
            throw new InvalidDischargeCancellationException("Cancellation operator must not exceed 100 characters");
        }
    }

    public UUID getId() {
        return id;
    }

    public Encounter getEncounter() {
        return encounter;
    }

    public EncounterLocation getLocation() {
        return location;
    }

    public OffsetDateTime getDischargedAt() {
        return dischargedAt;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public EncounterLocation getRestoredLocation() {
        return restoredLocation;
    }

}
