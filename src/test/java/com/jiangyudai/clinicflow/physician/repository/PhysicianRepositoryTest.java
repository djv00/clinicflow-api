package com.jiangyudai.clinicflow.physician.repository;

import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.repository.DepartmentRepository;
import com.jiangyudai.clinicflow.physician.entity.Physician;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class PhysicianRepositoryTest {

    @Autowired
    private PhysicianRepository physicians;
    @Autowired
    private DepartmentRepository departments;
    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsMultipleDepartmentsAndAllowsOtherPhysiciansToShareThem() {
        Department medicine = departments.save(new Department("MED", "Medicine"));
        Department surgery = departments.save(new Department("SURG", "Surgery"));
        Physician first = new Physician("PHY-001", "Maya", "Chen");
        first.addDepartment(medicine);
        first.addDepartment(surgery);
        Physician second = new Physician("PHY-002", "Alex", "Martin");
        second.addDepartment(medicine);
        physicians.save(first);
        physicians.saveAndFlush(second);
        entityManager.clear();

        Physician reloaded = physicians.findByPhysicianCode("PHY-001").orElseThrow();
        assertThat(reloaded.getFirstName()).isEqualTo("Maya");
        assertThat(reloaded.getLastName()).isEqualTo("Chen");
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getDepartments()).extracting(Department::getId)
                .containsExactlyInAnyOrder(medicine.getId(), surgery.getId());
        assertThat(physicians.findById(second.getId()).orElseThrow().getDepartments())
                .extracting(Department::getId).containsExactly(medicine.getId());
        assertThat(physicians.existsByPhysicianCode("PHY-001")).isTrue();
    }

    @Test
    void canCreatePhysicianBeforeAssigningDepartments() {
        Physician physician = physicians.saveAndFlush(new Physician("PHY-001", "Maya", "Chen"));
        entityManager.clear();

        assertThat(physicians.findById(physician.getId()).orElseThrow().getDepartments()).isEmpty();
    }

    @Test
    void ignoresDuplicateAffiliationEvenWhenDepartmentIsDetached() {
        Department detached = departments.saveAndFlush(new Department("MED", "Medicine"));
        entityManager.clear();
        Department managed = departments.findById(detached.getId()).orElseThrow();
        assertThat(managed).isNotSameAs(detached);
        Physician physician = new Physician("PHY-001", "Maya", "Chen");
        physician.addDepartment(managed);
        physician.addDepartment(detached);
        physicians.saveAndFlush(physician);
        entityManager.clear();

        assertThat(physicians.findById(physician.getId()).orElseThrow().getDepartments())
                .extracting(Department::getId).containsExactly(detached.getId());
    }

    @Test
    void removingAffiliationPreservesSharedDepartmentAndOtherPhysician() {
        Department department = departments.save(new Department("MED", "Medicine"));
        Physician first = new Physician("PHY-001", "Maya", "Chen");
        Physician second = new Physician("PHY-002", "Alex", "Martin");
        first.addDepartment(department);
        second.addDepartment(department);
        physicians.save(first);
        physicians.saveAndFlush(second);
        entityManager.clear();

        physicians.findById(first.getId()).orElseThrow().removeDepartment(department.getId());
        physicians.flush();
        entityManager.clear();

        assertThat(physicians.findById(first.getId()).orElseThrow().getDepartments()).isEmpty();
        assertThat(physicians.findById(second.getId()).orElseThrow().getDepartments())
                .extracting(Department::getId).containsExactly(department.getId());
        assertThat(departments.existsById(department.getId())).isTrue();
    }

    @Test
    void deletingPhysicianDoesNotCascadeToSharedDepartments() {
        Department department = departments.save(new Department("MED", "Medicine"));
        Physician first = new Physician("PHY-001", "Maya", "Chen");
        Physician second = new Physician("PHY-002", "Alex", "Martin");
        first.addDepartment(department);
        second.addDepartment(department);
        physicians.save(first);
        physicians.saveAndFlush(second);
        entityManager.clear();

        physicians.deleteById(first.getId());
        physicians.flush();
        entityManager.clear();

        assertThat(physicians.existsById(first.getId())).isFalse();
        assertThat(departments.existsById(department.getId())).isTrue();
        assertThat(physicians.findById(second.getId()).orElseThrow().getDepartments())
                .extracting(Department::getId).containsExactly(department.getId());
    }

    @Test
    void deactivationRetainsAffiliationsAndAllowsLaterReactivation() {
        Department department = departments.save(new Department("MED", "Medicine"));
        Physician physician = new Physician("PHY-001", "Maya", "Chen");
        physician.addDepartment(department);
        physicians.saveAndFlush(physician);
        physician.deactivate();
        physicians.flush();
        entityManager.clear();

        Physician inactive = physicians.findById(physician.getId()).orElseThrow();
        assertThat(inactive.isActive()).isFalse();
        assertThat(inactive.getDepartments()).extracting(Department::getId).containsExactly(department.getId());
        inactive.activate();
        inactive.rename("Mai", "Chen");
        physicians.flush();
        entityManager.clear();

        Physician reactivated = physicians.findById(physician.getId()).orElseThrow();
        assertThat(reactivated.isActive()).isTrue();
        assertThat(reactivated.getFirstName()).isEqualTo("Mai");
        assertThat(reactivated.getPhysicianCode()).isEqualTo("PHY-001");
    }

    @Test
    void databaseRejectsDuplicatePhysicianCodes() {
        physicians.saveAndFlush(new Physician("PHY-001", "Maya", "Chen"));

        assertThatThrownBy(() -> physicians.saveAndFlush(new Physician("PHY-001", "Alex", "Martin")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void affiliationChangePreventsStaleProfileFromOverwritingIt() {
        Department department = departments.saveAndFlush(new Department("MED", "Medicine"));
        Physician stale = physicians.saveAndFlush(new Physician("PHY-001", "Maya", "Chen"));
        stale.getDepartments().size();
        long originalVersion = stale.getVersion();
        entityManager.clear();

        Physician current = physicians.findById(stale.getId()).orElseThrow();
        current.addDepartment(departments.findById(department.getId()).orElseThrow());
        physicians.flush();
        assertThat(current.getVersion()).isGreaterThan(originalVersion);
        entityManager.clear();

        stale.rename("Mai", "Chen");
        assertThatThrownBy(() -> physicians.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void rejectsTransientDepartmentInsteadOfCreatingItThroughPhysician() {
        Physician physician = new Physician("PHY-001", "Maya", "Chen");

        assertThatThrownBy(() -> physician.addDepartment(new Department("MED", "Medicine")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Department must already be persisted");
        assertThat(departments.count()).isZero();
    }
}
