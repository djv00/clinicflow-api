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

@Service
@Transactional(readOnly = true)
public class EncounterService {

    private static final List<EncounterStatus> ACTIVE_STATUSES = List.of(
            EncounterStatus.ADMITTED,
            EncounterStatus.IN_DEPARTMENT
    );

    private final EncounterRepository encounterRepository;
    private final PatientService patientService;

    public EncounterService(
            EncounterRepository encounterRepository,
            PatientService patientService
    ) {
        this.encounterRepository = encounterRepository;
        this.patientService = patientService;
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

    public Encounter getEncounter(UUID id) {
        return encounterRepository.findById(id)
                .orElseThrow(() -> new EncounterNotFoundException(id));
    }
}