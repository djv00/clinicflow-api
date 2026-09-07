package com.jiangyudai.clinicflow.encounter.entity;

import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.encounter.exception.InvalidDischargeTimeException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "encounters",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_encounters_encounter_number",
                        columnNames = "encounter_number"
                )
        }
)
public class Encounter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "encounter_number",
            nullable = false,
            updatable = false,
            length = 50
    )
    private String encounterNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "patient_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_encounters_patient")
    )
    private Patient patient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EncounterStatus status;

    @Column(name = "admitted_at", nullable = false)
    private OffsetDateTime admittedAt;

    @Column(name = "discharged_at")
    private OffsetDateTime dischargedAt;

    // Required by JPA.
    protected Encounter() {
    }

    public Encounter(
            String encounterNumber,
            Patient patient,
            OffsetDateTime admittedAt
    ) {
        this.encounterNumber = encounterNumber;
        this.patient = patient;
        this.admittedAt = admittedAt;
        this.status = EncounterStatus.ADMITTED;
    }

    /**
     * Advances an admitted encounter to its first department location.
     */
    public void admitToDepartment() {
        if (status != EncounterStatus.ADMITTED) {
            throw new InvalidEncounterStatusException(
                    status,
                    EncounterStatus.ADMITTED
            );
        }

        status = EncounterStatus.IN_DEPARTMENT;
    }

    /**
     * Records discharge after the encounter has entered a department.
     */
    public void dischargeAt(OffsetDateTime dischargedAt) {
        if (status != EncounterStatus.IN_DEPARTMENT) {
            throw new InvalidEncounterStatusException(
                    status,
                    EncounterStatus.IN_DEPARTMENT
            );
        }

        if (dischargedAt == null) {
            throw new InvalidDischargeTimeException(
                    "Discharge time is required"
            );
        }

        if (dischargedAt.isBefore(admittedAt)) {
            throw new InvalidDischargeTimeException(
                    "Discharge time cannot be before hospital admission"
            );
        }

        if (dischargedAt.isAfter(OffsetDateTime.now())) {
            throw new InvalidDischargeTimeException(
                    "Discharge time cannot be in the future"
            );
        }

        this.dischargedAt = dischargedAt;
        status = EncounterStatus.DISCHARGED;
    }

    public UUID getId() {
        return id;
    }

    public String getEncounterNumber() {
        return encounterNumber;
    }

    public Patient getPatient() {
        return patient;
    }

    public EncounterStatus getStatus() {
        return status;
    }

    public OffsetDateTime getAdmittedAt() {
        return admittedAt;
    }

    public OffsetDateTime getDischargedAt() {
        return dischargedAt;
    }
}
