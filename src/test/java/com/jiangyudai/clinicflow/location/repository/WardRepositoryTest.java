package com.jiangyudai.clinicflow.location.repository;

import com.jiangyudai.clinicflow.location.entity.Ward;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class WardRepositoryTest {

    @Autowired
    private WardRepository wardRepository;

    @Test
    void savesAndFindsWardByCode() {
        wardRepository.saveAndFlush(
                new Ward("WARD-A", "General Inpatient Ward")
        );

        Ward ward = wardRepository
                .findByWardCode("WARD-A")
                .orElseThrow();

        assertThat(ward.getWardName())
                .isEqualTo("General Inpatient Ward");
        assertThat(ward.isActive()).isTrue();
    }
}