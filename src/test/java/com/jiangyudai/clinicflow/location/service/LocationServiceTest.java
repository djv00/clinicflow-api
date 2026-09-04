package com.jiangyudai.clinicflow.location.service;

import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.location.exception.InvalidLocationException;
import com.jiangyudai.clinicflow.location.exception.LocationNotFoundException;
import com.jiangyudai.clinicflow.location.repository.BedRepository;
import com.jiangyudai.clinicflow.location.repository.DepartmentRepository;
import com.jiangyudai.clinicflow.location.repository.WardRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );

    private static final UUID WARD_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
    );

    private static final UUID BED_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
    );

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private WardRepository wardRepository;

    @Mock
    private BedRepository bedRepository;

    @InjectMocks
    private LocationService locationService;

    @Test
    void returnsActiveDepartmentWardAndBed() {
        Department department = new Department("CARD", "Cardiology");
        Ward ward = new Ward("WARD-A", "General Inpatient Ward");
        Bed bed = createBedInWard(WARD_ID);

        when(departmentRepository.findById(DEPARTMENT_ID))
                .thenReturn(Optional.of(department));
        when(wardRepository.findById(WARD_ID))
                .thenReturn(Optional.of(ward));
        when(bedRepository.findById(BED_ID))
                .thenReturn(Optional.of(bed));

        assertThat(locationService.getActiveDepartment(DEPARTMENT_ID))
                .isSameAs(department);
        assertThat(locationService.getActiveWard(WARD_ID))
                .isSameAs(ward);
        assertThat(locationService.getActiveBed(BED_ID, WARD_ID))
                .isSameAs(bed);
    }

    @Test
    void rejectsMissingDepartment() {
        when(departmentRepository.findById(DEPARTMENT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                locationService.getActiveDepartment(DEPARTMENT_ID)
        ).isInstanceOf(LocationNotFoundException.class);
    }

    @Test
    void rejectsInactiveBed() {
        Bed bed = mock(Bed.class);

        when(bed.isActive()).thenReturn(false);
        when(bedRepository.findById(BED_ID))
                .thenReturn(Optional.of(bed));

        assertThatThrownBy(() ->
                locationService.getActiveBed(BED_ID, WARD_ID)
        ).isInstanceOf(InvalidLocationException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    void rejectsBedFromAnotherWard() {
        UUID anotherWardId = UUID.fromString(
                "44444444-4444-4444-4444-444444444444"
        );

        Bed bed = createBedInWard(anotherWardId);

        when(bedRepository.findById(BED_ID))
                .thenReturn(Optional.of(bed));

        assertThatThrownBy(() ->
                locationService.getActiveBed(BED_ID, WARD_ID)
        ).isInstanceOf(InvalidLocationException.class)
                .hasMessageContaining("does not belong to ward");
    }

    private Bed createBedInWard(UUID wardId) {
        Ward ward = mock(Ward.class);
        when(ward.getId()).thenReturn(wardId);

        return new Bed("01", ward);
    }
}