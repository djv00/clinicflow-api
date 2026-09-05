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
        name = "departments",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_departments_department_code",
                        columnNames = "department_code"
                )
        }
)
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "department_code",
            nullable = false,
            updatable = false,
            length = 30
    )
    private String departmentCode;

    @Column(
            name = "department_name",
            nullable = false,
            length = 100
    )
    private String departmentName;

    @Column(nullable = false)
    private boolean active;

    // Required by JPA.
    protected Department() {
    }

    public Department(String departmentCode, String departmentName) {
        this.departmentCode = departmentCode;
        this.departmentName = departmentName;
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public String getDepartmentName() {
        return departmentName;
    }

    public boolean isActive() {
        return active;
    }
}
