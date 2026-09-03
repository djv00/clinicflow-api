package com.jiangyudai.clinicflow.location.repository;

import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Ward;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class BedRepositoryTest {

    @Autowired
    private WardRepository wardRepository;

    @Autowired
    private BedRepository bedRepository;

    @Test
    void savesAndFindsBedWithinWard() {
        Ward ward = wardRepository.saveAndFlush(
                new Ward("WARD-A", "General Inpatient Ward")
        );

        bedRepository.saveAndFlush(
                new Bed("01", ward)
        );

        Bed bed = bedRepository
                .findByWard_IdAndBedNumber(ward.getId(), "01")
                .orElseThrow();

        assertThat(bed.getWard().getId()).isEqualTo(ward.getId());
        assertThat(bed.isActive()).isTrue();
    }
}