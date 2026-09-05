package com.jiangyudai.clinicflow.location.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(
        name = "wards",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_wards_ward_code",
                        columnNames = "ward_code"
                )
        }
)
public class Ward {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "ward_code",
            nullable = false,
            updatable = false,
            length = 30
    )
    private String wardCode;

    @Column(
            name = "ward_name",
            nullable = false,
            length = 100
    )
    private String wardName;

    @Column(nullable = false)
    private boolean active;

    // Required by JPA.
    protected Ward() {
    }

    public Ward(String wardCode, String wardName) {
        this.wardCode = wardCode;
        this.wardName = wardName;
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public String getWardCode() {
        return wardCode;
    }

    public String getWardName() {
        return wardName;
    }

    public boolean isActive() {
        return active;
    }
}
