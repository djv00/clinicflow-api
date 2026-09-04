package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.ActiveEncounterExistsException;
import com.jiangyudai.clinicflow.encounter.exception.DuplicateEncounterNumberException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterNotFoundException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidAdmissionTimeException;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.exception.ActiveEncounterLocationExistsException;
import com.jiangyudai.clinicflow.encounter.exception.BedOccupiedException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidDepartmentAdmissionTimeException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;
import com.jiangyudai.clinicflow.encounter.repository.EncounterLocationRepository;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.location.service.LocationService;

@Service
@Transactional(readOnly = true)
public class EncounterService {

    private static final List<EncounterStatus> ACTIVE_STATUSES = List.of(
            EncounterStatus.ADMITTED,
            EncounterStatus.IN_DEPARTMENT
    );

    private final EncounterLocationRepository encounterLocationRepository;
    private final LocationService locationService;

    private final EncounterRepository encounterRepository;
    private final PatientService patientService;

    public EncounterService(
            EncounterRepository encounterRepository,
            PatientService patientService,
            EncounterLocationRepository encounterLocationRepository,
            LocationService locationService
    ) {
        this.encounterRepository = encounterRepository;
        this.patientService = patientService;
        this.encounterLocationRepository = encounterLocationRepository;
        this.locationService = locationService;
    }

    @Transactional
    public Encounter admitPatient(
            UUID patientId,
            String encounterNumber,
            OffsetDateTime admittedAt
    ) {
        if (admittedAt.isAfter(OffsetDateTime.now())) {
            throw new InvalidAdmissionTimeException(admittedAt);
        }

        Patient patient = patientService.getPatient(patientId);

        if (encounterRepository.existsByEncounterNumber(encounterNumber)) {
            throw new DuplicateEncounterNumberException(encounterNumber);
        }

        if (encounterRepository.existsByPatient_IdAndStatusIn(
                patientId,
                ACTIVE_STATUSES
        )) {
            throw new ActiveEncounterExistsException(patientId);
        }

        Encounter encounter = new Encounter(
                encounterNumber,
                patient,
                admittedAt
        );

        return encounterRepository.save(encounter);
    }

    //入科
    @Transactional
    public EncounterLocation admitToDepartment(
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

        Encounter encounter = getEncounter(encounterId);

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
            bed = locationService.getActiveBed(bedId, wardId);

            if (encounterLocationRepository
                    .existsByBed_IdAndEndedAtIsNull(bedId)) {
                throw new BedOccupiedException(bedId);
            }
        }

        EncounterLocation location = new EncounterLocation(
                encounter,
                department,
                ward,
                bed,
                startedAt
        );

        encounter.admitToDepartment();

        return encounterLocationRepository.save(location);
    }

    public Encounter getEncounter(UUID id) {
        return encounterRepository.findById(id)
                .orElseThrow(() -> new EncounterNotFoundException(id));
    }
}