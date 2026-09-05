package com.jiangyudai.clinicflow.location.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(
        name = "beds",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_beds_ward_bed_number",
                        columnNames = {"ward_id", "bed_number"}
                )
        }
)
public class Bed {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "bed_number",
            nullable = false,
            updatable = false,
            length = 30
    )
    private String bedNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ward_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_beds_ward")
    )
    private Ward ward;

    @Column(nullable = false)
    private boolean active;

    // Required by JPA.
    protected Bed() {
    }

    public Bed(String bedNumber, Ward ward) {
        this.bedNumber = bedNumber;
        this.ward = ward;
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public String getBedNumber() {
        return bedNumber;
    }

    public Ward getWard() {
        return ward;
    }

    public boolean isActive() {
        return active;
    }
}
