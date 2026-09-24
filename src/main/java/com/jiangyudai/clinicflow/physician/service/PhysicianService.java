package com.jiangyudai.clinicflow.physician.service;

import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.exception.LocationNotFoundException;
import com.jiangyudai.clinicflow.location.repository.DepartmentRepository;
import com.jiangyudai.clinicflow.physician.dto.PhysicianPageResponse;
import com.jiangyudai.clinicflow.physician.dto.PhysicianResponse;
import com.jiangyudai.clinicflow.physician.entity.Physician;
import com.jiangyudai.clinicflow.physician.exception.*;
import com.jiangyudai.clinicflow.physician.repository.PhysicianRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PhysicianService {
    private final PhysicianRepository physicians;
    private final DepartmentRepository departments;

    public PhysicianService(PhysicianRepository physicians, DepartmentRepository departments) {
        this.physicians = physicians;
        this.departments = departments;
    }

    public PhysicianResponse get(UUID id) {
        return PhysicianResponse.from(find(id));
    }

    /** Searches literal name/code fragments and pages physicians before fetching affiliations. */
    public PhysicianPageResponse search(String keyword, UUID departmentId, Boolean active, int page, int size) {
        String search = keyword == null ? "" : keyword.strip();
        String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var result = physicians.search(pattern, departmentId, active,
                PageRequest.of(page, size, Sort.by("lastName", "firstName", "physicianCode")));
        if (result.isEmpty()) {
            return new PhysicianPageResponse(List.of(), page, size, result.getTotalElements(), result.getTotalPages());
        }
        // Fetch affiliations separately to keep pagination in SQL and avoid one query per physician.
        List<UUID> ids = result.getContent().stream().map(Physician::getId).toList();
        Map<UUID, PhysicianResponse> details = physicians.findWithDepartmentsByIdIn(ids).stream()
                .map(PhysicianResponse::from).collect(Collectors.toMap(PhysicianResponse::id, response -> response));
        return new PhysicianPageResponse(ids.stream().map(details::get).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    /** Registers a physician with existing, active service departments. */
    @Transactional
    public PhysicianResponse create(String code, String firstName, String lastName, Set<UUID> departmentIds) {
        Physician physician = new Physician(code, firstName, lastName);
        if (physicians.existsByPhysicianCode(physician.getPhysicianCode())) {
            throw new DuplicatePhysicianCodeException(physician.getPhysicianCode());
        }
        resolveDepartments(departmentIds, Set.of()).forEach(physician::addDepartment);
        try {
            return PhysicianResponse.from(physicians.saveAndFlush(physician));
        } catch (DataIntegrityViolationException exception) {
            // The unique constraint also covers two requests that both passed the existence check.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null
                        && violation.getConstraintName().toLowerCase(Locale.ROOT).contains("uk_physicians_physician_code")) {
                    throw new DuplicatePhysicianCodeException(physician.getPhysicianCode());
                }
            }
            throw exception;
        }
    }

    /** Replaces the editable profile and current affiliations using the client's last-seen version. */
    @Transactional
    public PhysicianResponse update(UUID id, String firstName, String lastName, Set<UUID> departmentIds, long version) {
        Physician physician = find(id);
        checkVersion(physician, version);
        Set<UUID> existing = physician.getDepartments().stream().map(Department::getId).collect(Collectors.toSet());
        List<Department> selected = resolveDepartments(departmentIds, existing);
        physician.rename(firstName, lastName);
        existing.stream().filter(departmentId -> !departmentIds.contains(departmentId))
                .forEach(physician::removeDepartment);
        selected.forEach(physician::addDepartment);
        physicians.flush();
        return PhysicianResponse.from(physician);
    }

    /** Changes availability without removing the physician's affiliations. */
    @Transactional
    public PhysicianResponse changeActive(UUID id, boolean active, long version) {
        Physician physician = find(id);
        checkVersion(physician, version);
        if (active) {
            physician.activate();
        } else {
            physician.deactivate();
        }
        physicians.flush();
        return PhysicianResponse.from(physician);
    }

    private Physician find(UUID id) {
        return physicians.findWithDepartmentsById(id).orElseThrow(() -> new PhysicianNotFoundException(id));
    }

    private void checkVersion(Physician physician, long version) {
        if (physician.getVersion() != version) {
            throw new PhysicianVersionConflictException();
        }
    }

    private List<Department> resolveDepartments(Set<UUID> ids, Set<UUID> existing) {
        List<Department> selected = departments.findAllById(ids);
        Set<UUID> found = selected.stream().map(Department::getId).collect(Collectors.toSet());
        for (UUID id : ids) {
            if (!found.contains(id)) {
                throw new LocationNotFoundException("Department", id);
            }
        }
        for (Department department : selected) {
            if (!department.isActive() && !existing.contains(department.getId())) {
                throw new InvalidPhysicianDepartmentException(department.getId());
            }
        }
        return selected;
    }
}
