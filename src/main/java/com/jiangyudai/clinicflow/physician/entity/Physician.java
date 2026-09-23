package com.jiangyudai.clinicflow.physician.entity;

import com.jiangyudai.clinicflow.location.entity.Department;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "physicians", uniqueConstraints =
        @UniqueConstraint(name = "uk_physicians_physician_code", columnNames = "physician_code"))
public class Physician {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "physician_code", nullable = false, updatable = false, length = 30)
    private String physicianCode;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "physician_departments",
            joinColumns = @JoinColumn(name = "physician_id", nullable = false,
                    foreignKey = @ForeignKey(name = "fk_physician_departments_physician")),
            inverseJoinColumns = @JoinColumn(name = "department_id", nullable = false,
                    foreignKey = @ForeignKey(name = "fk_physician_departments_department")))
    private Set<Department> departments = new HashSet<>();

    protected Physician() {
    }

    public Physician(String physicianCode, String firstName, String lastName) {
        this.physicianCode = requiredText(physicianCode, 30, "Physician code");
        rename(firstName, lastName);
        this.active = true;
    }

    public void rename(String firstName, String lastName) {
        String validatedFirstName = requiredText(firstName, 100, "First name");
        String validatedLastName = requiredText(lastName, 100, "Last name");
        this.firstName = validatedFirstName;
        this.lastName = validatedLastName;
    }

    /** Adds an existing department where this physician may provide care. */
    public void addDepartment(Department department) {
        Assert.notNull(department, "Department is required");
        Assert.notNull(department.getId(), "Department must already be persisted");
        // Compare IDs because the same department can arrive as a detached instance.
        if (departments.stream().noneMatch(existing -> existing.getId().equals(department.getId()))) {
            departments.add(department);
        }
    }

    /** Removes only the affiliation, leaving the shared department intact. */
    public void removeDepartment(UUID departmentId) {
        Assert.notNull(departmentId, "Department ID is required");
        departments.removeIf(department -> departmentId.equals(department.getId()));
    }

    public void deactivate() {
        // Retain affiliations when a physician is no longer available for new work.
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public String getPhysicianCode() {
        return physicianCode;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version;
    }

    public Set<Department> getDepartments() {
        return Collections.unmodifiableSet(departments);
    }

    private static String requiredText(String value, int maxLength, String field) {
        Assert.hasText(value, field + " is required");
        String normalized = value.strip();
        Assert.isTrue(normalized.length() <= maxLength,
                field + " must not exceed " + maxLength + " characters");
        return normalized;
    }
}
