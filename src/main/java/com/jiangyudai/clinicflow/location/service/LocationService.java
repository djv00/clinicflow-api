package com.jiangyudai.clinicflow.location.service;

import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.location.exception.InvalidLocationException;
import com.jiangyudai.clinicflow.location.exception.LocationNotFoundException;
import com.jiangyudai.clinicflow.location.repository.BedRepository;
import com.jiangyudai.clinicflow.location.repository.DepartmentRepository;
import com.jiangyudai.clinicflow.location.repository.WardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class LocationService {

    private final DepartmentRepository departmentRepository;
    private final WardRepository wardRepository;
    private final BedRepository bedRepository;

    public LocationService(
            DepartmentRepository departmentRepository,
            WardRepository wardRepository,
            BedRepository bedRepository
    ) {
        this.departmentRepository = departmentRepository;
        this.wardRepository = wardRepository;
        this.bedRepository = bedRepository;
    }

    public Department getActiveDepartment(UUID departmentId) {
        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() ->
                        new LocationNotFoundException(
                                "Department",
                                departmentId
                        )
                );

        if (!department.isActive()) {
            throw new InvalidLocationException(
                    "Department is inactive: " + departmentId
            );
        }

        return department;
    }

    public Ward getActiveWard(UUID wardId) {
        Ward ward = wardRepository.findById(wardId)
                .orElseThrow(() ->
                        new LocationNotFoundException("Ward", wardId)
                );

        if (!ward.isActive()) {
            throw new InvalidLocationException(
                    "Ward is inactive: " + wardId
            );
        }

        return ward;
    }

    public Bed getActiveBed(UUID bedId, UUID wardId) {
        Bed bed = bedRepository.findById(bedId)
                .orElseThrow(() ->
                        new LocationNotFoundException("Bed", bedId)
                );

        return validateBed(bed, wardId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Bed getActiveBedForUpdate(UUID bedId, UUID wardId) {
        Bed bed = bedRepository.findByIdForUpdate(bedId)
                .orElseThrow(() ->
                        new LocationNotFoundException("Bed", bedId)
                );

        return validateBed(bed, wardId);
    }

    private Bed validateBed(Bed bed, UUID wardId) {
        if (!bed.isActive()) {
            throw new InvalidLocationException(
                    "Bed is inactive: " + bed.getId()
            );
        }

        if (!bed.getWard().getId().equals(wardId)) {
            throw new InvalidLocationException(
                    "Bed does not belong to ward: " + wardId
            );
        }

        return bed;
    }
}