package com.jiangyudai.clinicflow.location.repository;

import com.jiangyudai.clinicflow.location.entity.Department;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class DepartmentRepositoryTest {

    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    void savesAndFindsDepartmentByCode() {
        departmentRepository.saveAndFlush(
                new Department("CARD", "Cardiology")
        );

        Department department = departmentRepository
                .findByDepartmentCode("CARD")
                .orElseThrow();

        assertThat(department.getDepartmentName())
                .isEqualTo("Cardiology");
        assertThat(department.isActive()).isTrue();
    }
}