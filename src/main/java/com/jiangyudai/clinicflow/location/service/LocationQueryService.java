package com.jiangyudai.clinicflow.location.service;

import com.jiangyudai.clinicflow.location.dto.BedResponse;
import com.jiangyudai.clinicflow.location.dto.DepartmentResponse;
import com.jiangyudai.clinicflow.location.dto.WardResponse;
import com.jiangyudai.clinicflow.location.exception.LocationNotFoundException;
import com.jiangyudai.clinicflow.location.repository.BedRepository;
import com.jiangyudai.clinicflow.location.repository.DepartmentRepository;
import com.jiangyudai.clinicflow.location.repository.WardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class LocationQueryService {

    private final DepartmentRepository departmentRepository;
    private final WardRepository wardRepository;
    private final BedRepository bedRepository;

    public LocationQueryService(DepartmentRepository departmentRepository, WardRepository wardRepository,
                                BedRepository bedRepository) {
        this.departmentRepository = departmentRepository;
        this.wardRepository = wardRepository;
        this.bedRepository = bedRepository;
    }

    public List<DepartmentResponse> getDepartments(Boolean active) {
        return departmentRepository.findForLookup(active).stream().map(DepartmentResponse::from).toList();
    }

    public DepartmentResponse getDepartment(UUID id) {
        return departmentRepository.findById(id).map(DepartmentResponse::from)
                .orElseThrow(() -> new LocationNotFoundException("Department", id));
    }

    public List<WardResponse> getWards(Boolean active) {
        return wardRepository.findForLookup(active).stream().map(WardResponse::from).toList();
    }

    public WardResponse getWard(UUID id) {
        return wardRepository.findById(id).map(WardResponse::from)
                .orElseThrow(() -> new LocationNotFoundException("Ward", id));
    }

    public List<BedResponse> getBeds(UUID wardId, Boolean active, Boolean occupied) {
        return bedRepository.findForLookup(null, wardId, active, occupied);
    }

    public BedResponse getBed(UUID id) {
        return bedRepository.findForLookup(id, null, null, null).stream().findFirst()
                .orElseThrow(() -> new LocationNotFoundException("Bed", id));
    }
}
