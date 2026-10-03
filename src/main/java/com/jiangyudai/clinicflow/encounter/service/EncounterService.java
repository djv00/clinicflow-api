package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.dto.EncounterResponse;
import com.jiangyudai.clinicflow.encounter.dto.EncounterLocationResponse;
import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterDischarge;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.entity.PhysicianAssignmentEndReason;
import com.jiangyudai.clinicflow.encounter.exception.*;
import com.jiangyudai.clinicflow.encounter.repository.EncounterDischargeRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterLocationRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.location.service.LocationService;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Coordinates hospital admission and encounter state changes.
 *
 * @author Jiangyu Dai
 */
@Service
@Transactional(readOnly = true)
public class EncounterService {

    // These states still represent a patient receiving hospital care.
    private static final List<EncounterStatus> ACTIVE_STATUSES = List.of(
            EncounterStatus.ADMITTED,
            EncounterStatus.IN_DEPARTMENT
    );

    private final EncounterLocationRepository encounterLocationRepository;
    private final LocationService locationService;

    private final EncounterRepository encounterRepository;
    private final PatientService patientService;
    private final EncounterDischargeRepository encounterDischargeRepository;
    private final EncounterPhysicianService physicianService;

    public EncounterService(
            EncounterRepository encounterRepository,
            PatientService patientService,
            EncounterLocationRepository encounterLocationRepository,
            LocationService locationService,
            EncounterDischargeRepository encounterDischargeRepository,
            EncounterPhysicianService physicianService
    ) {
        this.encounterRepository = encounterRepository;
        this.patientService = patientService;
        this.encounterLocationRepository = encounterLocationRepository;
        this.locationService = locationService;
        this.encounterDischargeRepository = encounterDischargeRepository;
        this.physicianService = physicianService;
    }

    /**
     * Opens a hospital encounter after checking admission rules.
     */
    @Transactional
    public EncounterResponse admitPatient(
            UUID patientId,
            String encounterNumber,
            OffsetDateTime admittedAt
    ) {
        encounterNumber = encounterNumber.trim();
        if (admittedAt.isAfter(OffsetDateTime.now())) {
            throw new InvalidAdmissionTimeException(admittedAt);
        }

        Patient patient = patientService.getPatientForUpdate(patientId);

        if (encounterRepository.existsByEncounterNumber(encounterNumber)) {
            throw new DuplicateEncounterNumberException(encounterNumber);
        }

        if (encounterRepository.existsByPatient_IdAndStatusIn(
                patientId,
                ACTIVE_STATUSES
        )) {
            throw new ActiveEncounterExistsException(patientId);
        }

        if (encounterRepository.existsByPatient_IdAndStatusAndDischargedAtAfter(
                patientId, EncounterStatus.DISCHARGED, admittedAt
        )) {
            throw new EncounterHistoryConflictException(patientId);
        }

        Encounter encounter = new Encounter(
                encounterNumber,
                patient,
                admittedAt
        );

        try {
            return EncounterResponse.from(encounterRepository.saveAndFlush(encounter));
        } catch (DataIntegrityViolationException exception) {
            // The patient lock does not serialize admission numbers across different patients.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uk_encounters_encounter_number".equalsIgnoreCase(violation.getConstraintName())) {
                    throw new DuplicateEncounterNumberException(encounterNumber);
                }
            }
            throw exception;
        }
    }

    /**
     * Places an admitted encounter in a department. A bed may be assigned later.
     *
     * @param bedId bed to assign, or {@code null} when no bed is assigned yet
     */
    @Transactional
    public EncounterLocationResponse admitToDepartment(
            UUID encounterId,
            UUID departmentId,
            UUID wardId,
            UUID bedId,
            OffsetDateTime startedAt
    ) {
        if (startedAt == null) {
            throw new InvalidDepartmentAdmissionTimeException(
                    "Department admission time is required"
            );
        }

        // Keep the lock order Encounter -> Bed for every location workflow.
        Encounter encounter = encounterRepository
                .findByIdForUpdate(encounterId)
                .orElseThrow(() -> new EncounterNotFoundException(encounterId));

        if (encounter.getStatus() != EncounterStatus.ADMITTED) {
            throw new InvalidEncounterStatusException(
                    encounter.getStatus(),
                    EncounterStatus.ADMITTED
            );
        }

        if (startedAt.isBefore(encounter.getAdmittedAt())) {
            throw new InvalidDepartmentAdmissionTimeException(
                    "Department admission time cannot be before hospital admission"
            );
        }

        if (startedAt.isAfter(OffsetDateTime.now())) {
            throw new InvalidDepartmentAdmissionTimeException(
                    "Department admission time cannot be in the future"
            );
        }

        if (encounterLocationRepository
                .existsByEncounter_IdAndEndedAtIsNull(encounterId)) {
            throw new ActiveEncounterLocationExistsException(encounterId);
        }

        Department department =
                locationService.getActiveDepartment(departmentId);
        Ward ward = locationService.getActiveWard(wardId);

        Bed bed = null;

        if (bedId != null) {
            // Hold the bed lock until the occupancy check and insert are committed.
            bed = locationService.getActiveBedForUpdate(bedId, wardId);

            if (encounterLocationRepository
                    .existsByBed_IdAndEndedAtIsNull(bedId)) {
                throw new BedOccupiedException(bedId);
            }
            checkBedHistory(bedId, startedAt);
        }

        EncounterLocation location = new EncounterLocation(
                encounter,
                department,
                ward,
                bed,
                startedAt
        );

        // The state change and location record are committed as one transaction.
        encounter.admitToDepartment();

        return EncounterLocationResponse.from(encounterLocationRepository.save(location));
    }

    /**
     * Moves an encounter to a new department, ward, or bed.
     */
    @Transactional
    public EncounterLocationResponse transferEncounter(
            UUID encounterId,
            UUID departmentId,
            UUID wardId,
            UUID bedId,
            OffsetDateTime transferredAt,
            String operator,
            UUID expectedLocationId
    ) {
        if (transferredAt == null) {
            throw new InvalidEncounterTransferTimeException(
                    "Transfer time is required"
            );
        }

        // Preserve the Encounter -> Bed lock order used by location workflows.
        Encounter encounter = encounterRepository
                .findByIdForUpdate(encounterId)
                .orElseThrow(() ->
                        new EncounterNotFoundException(encounterId)
                );

        if (encounter.getStatus() != EncounterStatus.IN_DEPARTMENT) {
            throw new InvalidEncounterStatusException(
                    encounter.getStatus(),
                    EncounterStatus.IN_DEPARTMENT
            );
        }

        EncounterLocation currentLocation = encounterLocationRepository
                .findByEncounter_IdAndEndedAtIsNull(encounterId)
                .orElseThrow(() ->
                        new CurrentEncounterLocationNotFoundException(
                                encounterId
                        )
                );

        checkExpectedLocation(currentLocation, expectedLocationId);

        if (transferredAt.isBefore(currentLocation.getStartedAt())) {
            throw new InvalidEncounterTransferTimeException(
                    "Transfer time cannot be before the current location start time"
            );
        }

        if (transferredAt.isAfter(OffsetDateTime.now())) {
            throw new InvalidEncounterTransferTimeException(
                    "Transfer time cannot be in the future"
            );
        }

        Department department =
                locationService.getActiveDepartment(departmentId);
        Ward ward = locationService.getActiveWard(wardId);

        Bed bed = null;

        if (bedId != null) {
            bed = locationService.getActiveBedForUpdate(bedId, wardId);
        }

        if (isSameLocation(currentLocation, department, ward, bed)) {
            throw new SameEncounterLocationException(encounterId);
        }

        if (bed != null
                && encounterLocationRepository
                .existsByBed_IdAndEndedAtIsNullAndEncounter_IdNot(
                        bed.getId(),
                        encounterId
                )) {
            throw new BedOccupiedException(bed.getId());
        }

        if (bed != null) {
            checkBedHistory(bed.getId(), transferredAt);
        }

        EncounterLocation nextLocation = new EncounterLocation(
                encounter,
                department,
                ward,
                bed,
                transferredAt
        );

        if (!currentLocation.getDepartment().getId().equals(departmentId)) {
            physicianService.closeForCareEnd(encounterId, transferredAt, PhysicianAssignmentEndReason.DEPARTMENT_TRANSFER, operator);
        }

        // Location and physician history change within the same transaction.
        currentLocation.endAt(transferredAt);
        // Hibernate inserts before updates; release the open-location keys before inserting the replacement.
        encounterLocationRepository.flush();

        return EncounterLocationResponse.from(encounterLocationRepository.save(nextLocation));
    }

    /**
     * Discharges an encounter and closes its current location in one transaction.
     */
    @Transactional
    public EncounterResponse dischargeEncounter(
            UUID encounterId,
            OffsetDateTime dischargedAt,
            String operator,
            UUID expectedLocationId
    ) {
        if (dischargedAt == null) {
            throw new InvalidDischargeTimeException(
                    "Discharge time is required"
            );
        }

        // Serialize discharge with transfers and other changes to this encounter.
        Encounter encounter = encounterRepository
                .findByIdForUpdate(encounterId)
                .orElseThrow(() -> new EncounterNotFoundException(encounterId));

        if (encounter.getStatus() != EncounterStatus.IN_DEPARTMENT) {
            throw new InvalidEncounterStatusException(
                    encounter.getStatus(),
                    EncounterStatus.IN_DEPARTMENT
            );
        }

        EncounterLocation currentLocation = encounterLocationRepository
                .findByEncounter_IdAndEndedAtIsNull(encounterId)
                .orElseThrow(() ->
                        new CurrentEncounterLocationNotFoundException(encounterId)
                );

        checkExpectedLocation(currentLocation, expectedLocationId);

        if (dischargedAt.isBefore(currentLocation.getStartedAt())) {
            throw new InvalidDischargeTimeException(
                    "Discharge time cannot be before the current location start time"
            );
        }

        encounter.dischargeAt(dischargedAt);
        physicianService.closeForCareEnd(encounterId, dischargedAt, PhysicianAssignmentEndReason.DISCHARGE, operator);
        currentLocation.endAt(dischargedAt);
        encounterDischargeRepository.save(new EncounterDischarge(encounter, currentLocation));

        return EncounterResponse.from(encounter);
    }

    /**
     * Cancels an admission only while no department location has been recorded.
     */
    @Transactional
    public EncounterResponse cancelAdmission(
            UUID encounterId,
            OffsetDateTime cancelledAt,
            String cancelledBy
    ) {
        // Use the same lock as department admission so only one workflow can proceed.
        Encounter encounter = encounterRepository
                .findByIdForUpdate(encounterId)
                .orElseThrow(() -> new EncounterNotFoundException(encounterId));

        if (encounter.getStatus() != EncounterStatus.ADMITTED) {
            throw new InvalidEncounterStatusException(
                    encounter.getStatus(),
                    EncounterStatus.ADMITTED
            );
        }

        // Even closed history means department care has already been recorded.
        if (encounterLocationRepository.existsByEncounter_Id(encounterId)) {
            throw new EncounterLocationHistoryExistsException(encounterId);
        }

        encounter.cancelAdmission(cancelledAt, cancelledBy);

        return EncounterResponse.from(encounter);
    }

    /**
     * Cancels a mistaken discharge and continues location history from its effective time.
     */
    @Transactional
    public EncounterResponse cancelDischarge(
            UUID encounterId,
            OffsetDateTime cancelledAt,
            String cancelledBy
    ) {
        return cancelDischarge(encounterId, cancelledAt, cancelledBy, null);
    }

    /**
     * When supplied, the expected record prevents a stale form from cancelling a later discharge.
     */
    @Transactional
    public EncounterResponse cancelDischarge(
            UUID encounterId,
            OffsetDateTime cancelledAt,
            String cancelledBy,
            UUID expectedDischargeId
    ) {
        UUID patientId = encounterRepository.findPatientIdById(encounterId)
                .orElseThrow(() -> new EncounterNotFoundException(encounterId));

        // Patient -> Encounter -> Bed also protects against concurrent readmission.
        patientService.getPatientForUpdate(patientId);
        Encounter encounter = encounterRepository.findByIdForUpdate(encounterId)
                .orElseThrow(() -> new EncounterNotFoundException(encounterId));
        if (encounter.getStatus() != EncounterStatus.DISCHARGED) {
            throw new InvalidEncounterStatusException(encounter.getStatus(), EncounterStatus.DISCHARGED);
        }
        if (encounterRepository.existsByPatient_IdAndStatusIn(patientId, ACTIVE_STATUSES)) {
            throw new ActiveEncounterExistsException(patientId);
        }
        if (encounterLocationRepository.existsByEncounter_IdAndEndedAtIsNull(encounterId)) {
            throw new ActiveEncounterLocationExistsException(encounterId);
        }

        EncounterDischarge discharge = encounterDischargeRepository
                .findByEncounter_IdAndCancelledAtIsNull(encounterId)
                .orElseThrow(() -> new DischargeRecordConflictException("Current discharge record is missing"));
        if (expectedDischargeId != null && !expectedDischargeId.equals(discharge.getId())) {
            throw new DischargeRecordConflictException("The discharge record has changed. Reload it before cancelling.");
        }
        EncounterLocation previous = discharge.getLocation();
        if (encounter.getDischargedAt() == null
                || !discharge.getDischargedAt().isEqual(encounter.getDischargedAt())
                || previous.getEndedAt() == null
                || !previous.getEndedAt().isEqual(discharge.getDischargedAt())) {
            throw new DischargeRecordConflictException("Discharge record does not match the closed location");
        }

        discharge.validateCancellation(cancelledAt, cancelledBy);

        if (encounterRepository.existsConflictingEncounterAfterDischarge(
                patientId, encounterId, discharge.getDischargedAt(), EncounterStatus.ADMISSION_CANCELLED
        )) {
            throw new SubsequentEncounterExistsException(patientId);
        }

        Department department = locationService.getActiveDepartment(previous.getDepartment().getId());
        Ward ward = locationService.getActiveWard(previous.getWard().getId());
        Bed bed = null;
        if (previous.getBed() != null) {
            bed = locationService.getActiveBedForUpdate(previous.getBed().getId(), ward.getId());
            if (encounterLocationRepository.existsByBed_IdAndEndedAtIsNull(bed.getId())) {
                throw new BedOccupiedException(bed.getId());
            }
            checkBedHistory(bed.getId(), discharge.getDischargedAt());
        }

        // The operation time belongs to audit; effective occupancy continues from discharge.
        EncounterLocation restored = new EncounterLocation(
                encounter, department, ward, bed, discharge.getDischargedAt()
        );
        discharge.cancelAt(cancelledAt, cancelledBy, restored);
        encounter.cancelDischarge();
        encounterLocationRepository.save(restored);
        return EncounterResponse.from(encounter);
    }

    // The caller's placement must still be current after acquiring the encounter lock.
    private void checkExpectedLocation(EncounterLocation currentLocation, UUID expectedLocationId) {
        if (expectedLocationId == null || !expectedLocationId.equals(currentLocation.getId())) {
            throw new EncounterLocationChangedException();
        }
    }

    private void checkBedHistory(UUID bedId, OffsetDateTime startedAt) {
        if (encounterLocationRepository.existsClosedBedHistoryAfter(bedId, startedAt)) {
            throw new BedHistoryConflictException(bedId);
        }
    }

    private boolean isSameLocation(
            EncounterLocation currentLocation,
            Department department,
            Ward ward,
            Bed bed
    ) {
        boolean sameBed = currentLocation.getBed() == null
                ? bed == null
                : bed != null
                && currentLocation.getBed().getId().equals(bed.getId());

        return currentLocation.getDepartment().getId()
                .equals(department.getId())
                && currentLocation.getWard().getId().equals(ward.getId())
                && sameBed;
    }
}
