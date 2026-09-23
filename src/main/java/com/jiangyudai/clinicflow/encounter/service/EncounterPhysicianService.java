package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.dto.EncounterPhysicianAssignmentsResponse;
import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterPhysicianAssignment;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.entity.PhysicianAssignmentEndReason;
import com.jiangyudai.clinicflow.encounter.exception.*;
import com.jiangyudai.clinicflow.encounter.repository.EncounterLocationRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterPhysicianAssignmentRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.exception.LocationNotFoundException;
import com.jiangyudai.clinicflow.location.repository.DepartmentRepository;
import com.jiangyudai.clinicflow.physician.entity.Physician;
import com.jiangyudai.clinicflow.physician.exception.PhysicianNotFoundException;
import com.jiangyudai.clinicflow.physician.repository.PhysicianRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class EncounterPhysicianService {
    private final EncounterRepository encounters;
    private final EncounterLocationRepository locations;
    private final EncounterPhysicianAssignmentRepository assignments;
    private final PhysicianRepository physicians;
    private final DepartmentRepository departments;

    public EncounterPhysicianService(EncounterRepository encounters, EncounterLocationRepository locations,
                                    EncounterPhysicianAssignmentRepository assignments,
                                    PhysicianRepository physicians, DepartmentRepository departments) {
        this.encounters = encounters;
        this.locations = locations;
        this.assignments = assignments;
        this.physicians = physicians;
        this.departments = departments;
    }

    /** Assigns or hands over responsibility. A null expected assignment means the caller saw no current physician. */
    @Transactional
    public EncounterPhysicianAssignment assign(UUID encounterId, UUID physicianId, UUID expectedLocationId,
                                               UUID expectedAssignmentId, OffsetDateTime startedAt, String operator) {
        validateOperator(operator);
        Encounter encounter = lockInDepartment(encounterId);
        EncounterLocation location = currentLocation(encounterId, expectedLocationId);
        EncounterPhysicianAssignment current = expectedAssignment(encounterId, expectedAssignmentId);
        validateTime(encounterId, location, current, startedAt);
        if (current != null && current.getPhysician().getId().equals(physicianId)) {
            throw new PhysicianAssignmentConflictException("This physician is already responsible for the encounter");
        }
        if (physicianId == null) {
            throw new InvalidPhysicianAssignmentException("Physician is required");
        }

        // Encounter -> Physician -> Department; hold eligibility stable until the assignment commits.
        Physician physician = physicians.findByIdForRead(physicianId)
                .orElseThrow(() -> new PhysicianNotFoundException(physicianId));
        UUID departmentId = location.getDepartment().getId();
        Department department = departments.findByIdForRead(departmentId)
                .orElseThrow(() -> new LocationNotFoundException("Department", departmentId));
        if (!physician.isActive() || !department.isActive()
                || physician.getDepartments().stream().noneMatch(item -> item.getId().equals(departmentId))) {
            throw new InvalidPhysicianAssignmentException("Select an active physician affiliated with the current active department");
        }

        if (current != null) {
            current.endAt(startedAt, PhysicianAssignmentEndReason.REASSIGNED, operator);
            // PostgreSQL checks the open-assignment index immediately, before the replacement insert.
            assignments.flush();
        }
        return assignments.saveAndFlush(new EncounterPhysicianAssignment(encounter, physician, department, startedAt, operator));
    }

    /** Ends responsibility without a replacement, keeping the encounter in department care. */
    @Transactional
    public EncounterPhysicianAssignment release(UUID encounterId, UUID expectedLocationId,
                                                UUID expectedAssignmentId, OffsetDateTime endedAt, String operator) {
        validateOperator(operator);
        lockInDepartment(encounterId);
        EncounterLocation location = currentLocation(encounterId, expectedLocationId);
        EncounterPhysicianAssignment current = expectedAssignment(encounterId, expectedAssignmentId);
        if (current == null) {
            throw new PhysicianAssignmentConflictException("There is no current physician assignment to release");
        }
        validateTime(encounterId, location, current, endedAt);
        current.endAt(endedAt, PhysicianAssignmentEndReason.RELEASED, operator);
        assignments.flush();
        return current;
    }

    /** Reads responsibility history while preventing encounter workflows from changing it mid-read. */
    @Transactional
    public List<EncounterPhysicianAssignment> getHistory(UUID encounterId) {
        encounters.findByIdForRead(encounterId).orElseThrow(() -> new EncounterNotFoundException(encounterId));
        return assignments.findAllByEncounter_IdOrderByStartedAtAscIdAsc(encounterId);
    }

    /** Supplies the current location and assignment IDs together so clients can detect stale selections. */
    @Transactional
    public EncounterPhysicianAssignmentsResponse getAssignments(UUID encounterId) {
        Encounter encounter = encounters.findByIdForRead(encounterId)
                .orElseThrow(() -> new EncounterNotFoundException(encounterId));
        var location = locations.findByEncounter_IdAndEndedAtIsNull(encounterId).orElse(null);
        var history = assignments.findAllByEncounter_IdOrderByStartedAtAscIdAsc(encounterId);
        return EncounterPhysicianAssignmentsResponse.from(encounter, location, history);
    }

    /** Caller holds the encounter write lock; closure must commit with the transfer or discharge. */
    @Transactional(propagation = Propagation.MANDATORY)
    void closeForCareEnd(UUID encounterId, OffsetDateTime endedAt, PhysicianAssignmentEndReason reason, String operator) {
        validateOperator(operator);
        var current = assignments.findByEncounter_IdAndEndedAtIsNull(encounterId).orElse(null);
        checkHistoryTime(encounterId, current, endedAt);
        if (current != null) {
            current.endAt(endedAt, reason, operator);
        }
    }

    private Encounter lockInDepartment(UUID encounterId) {
        Encounter encounter = encounters.findByIdForUpdate(encounterId)
                .orElseThrow(() -> new EncounterNotFoundException(encounterId));
        if (encounter.getStatus() != EncounterStatus.IN_DEPARTMENT) {
            throw new InvalidEncounterStatusException(encounter.getStatus(), EncounterStatus.IN_DEPARTMENT);
        }
        return encounter;
    }

    private EncounterLocation currentLocation(UUID encounterId, UUID expectedLocationId) {
        var location = locations.findByEncounter_IdAndEndedAtIsNull(encounterId)
                .orElseThrow(() -> new CurrentEncounterLocationNotFoundException(encounterId));
        if (!location.getId().equals(expectedLocationId)) {
            throw new PhysicianAssignmentConflictException("The current location has changed. Reload before assigning or releasing a physician.");
        }
        return location;
    }

    private EncounterPhysicianAssignment expectedAssignment(UUID encounterId, UUID expectedId) {
        var current = assignments.findByEncounter_IdAndEndedAtIsNull(encounterId).orElse(null);
        if (!Objects.equals(current == null ? null : current.getId(), expectedId)) {
            throw new PhysicianAssignmentConflictException("The current physician assignment has changed. Reload before saving.");
        }
        return current;
    }

    private void validateTime(UUID encounterId, EncounterLocation location,
                              EncounterPhysicianAssignment current, OffsetDateTime time) {
        if (time == null || time.isAfter(OffsetDateTime.now()) || time.isBefore(location.getStartedAt())) {
            throw new InvalidPhysicianAssignmentException("Assignment time must be between the current location start and now");
        }
        checkHistoryTime(encounterId, current, time);
    }

    private void checkHistoryTime(UUID encounterId, EncounterPhysicianAssignment current, OffsetDateTime time) {
        if ((current != null && time.isBefore(current.getStartedAt()))
                || assignments.existsByEncounter_IdAndEndedAtAfter(encounterId, time)) {
            throw new PhysicianAssignmentConflictException("The effective time precedes recorded physician responsibility. Review the history before saving.");
        }
    }

    private static void validateOperator(String operator) {
        if (!StringUtils.hasText(operator) || operator.length() > 100) {
            throw new InvalidPhysicianAssignmentException("A valid authenticated operator is required");
        }
    }
}
