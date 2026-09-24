package com.jiangyudai.clinicflow.encounter.entity;

import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.physician.entity.Physician;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.util.Assert;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Records one period of inpatient physician responsibility within an encounter. */
@Entity
@Table(name = "encounter_physician_assignments", indexes = {
        @Index(name = "idx_physician_assignments_encounter_start", columnList = "encounter_id, started_at, id"),
        @Index(name = "idx_physician_assignments_physician", columnList = "physician_id"),
        @Index(name = "idx_physician_assignments_department", columnList = "department_id")
})
public class EncounterPhysicianAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_physician_assignments_encounter"))
    private Encounter encounter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "physician_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_physician_assignments_physician"))
    private Physician physician;

    // Retain the department of responsibility even if the physician's directory affiliations change.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_physician_assignments_department"))
    private Department department;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "assigned_by", nullable = false, updatable = false, length = 100)
    private String assignedBy;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 30)
    private PhysicianAssignmentEndReason endReason;

    @Column(name = "ended_by", length = 100)
    private String endedBy;

    @Version
    @Column(nullable = false)
    private long version;

    protected EncounterPhysicianAssignment() {
    }

    public EncounterPhysicianAssignment(Encounter encounter, Physician physician, Department department,
                                        OffsetDateTime startedAt, String assignedBy) {
        Assert.notNull(encounter, "Encounter is required");
        Assert.notNull(physician, "Physician is required");
        Assert.notNull(department, "Department is required");
        Assert.notNull(startedAt, "Assignment start time is required");
        Assert.isTrue(!startedAt.isBefore(encounter.getAdmittedAt()), "Assignment cannot start before hospital admission");
        Assert.isTrue(!startedAt.isAfter(OffsetDateTime.now()), "Assignment cannot start in the future");
        validateOperator(assignedBy);
        this.encounter = encounter;
        this.physician = physician;
        this.department = department;
        this.startedAt = startedAt;
        this.assignedBy = assignedBy;
    }

    /** Closes a responsibility period without changing its physician, department, or start audit. */
    public void endAt(OffsetDateTime endedAt, PhysicianAssignmentEndReason reason, String endedBy) {
        Assert.state(this.endedAt == null, "Physician assignment has already ended");
        Assert.notNull(endedAt, "Assignment end time is required");
        Assert.isTrue(!endedAt.isBefore(startedAt), "Assignment cannot end before it starts");
        Assert.isTrue(!endedAt.isAfter(OffsetDateTime.now()), "Assignment cannot end in the future");
        Assert.notNull(reason, "Assignment end reason is required");
        validateOperator(endedBy);
        this.endedAt = endedAt;
        this.endReason = reason;
        this.endedBy = endedBy;
    }

    private static void validateOperator(String operator) {
        Assert.hasText(operator, "Assignment operator is required");
        Assert.isTrue(operator.length() <= 100, "Assignment operator must not exceed 100 characters");
    }

    public UUID getId() {
        return id;
    }

    public Encounter getEncounter() {
        return encounter;
    }

    public Physician getPhysician() {
        return physician;
    }

    public Department getDepartment() {
        return department;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public String getAssignedBy() {
        return assignedBy;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public PhysicianAssignmentEndReason getEndReason() {
        return endReason;
    }

    public String getEndedBy() {
        return endedBy;
    }

    public long getVersion() {
        return version;
    }
}
