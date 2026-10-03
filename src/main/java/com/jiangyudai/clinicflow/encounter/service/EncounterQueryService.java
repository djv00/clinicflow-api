package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.dto.*;
import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterDischarge;
import com.jiangyudai.clinicflow.encounter.exception.EncounterNotFoundException;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterLocationRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterDischargeRepository;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Read models for one encounter or a patient's encounter history. */
@Service
@Transactional(readOnly = true)
public class EncounterQueryService {
    private final EncounterRepository encounterRepository;
    private final EncounterLocationRepository encounterLocationRepository;
    private final EncounterDischargeRepository encounterDischargeRepository;
    private final PatientService patientService;

    public EncounterQueryService(EncounterRepository encounterRepository,
                                 EncounterLocationRepository encounterLocationRepository,
                                 EncounterDischargeRepository encounterDischargeRepository,
                                 PatientService patientService) {
        this.encounterRepository = encounterRepository;
        this.encounterLocationRepository = encounterLocationRepository;
        this.encounterDischargeRepository = encounterDischargeRepository;
        this.patientService = patientService;
    }

    /**
     * Returns effective location history and discharge audit from one consistent workflow state.
     */
    // PostgreSQL row-locking reads need a writable transaction.
    @Transactional
    public EncounterTimelineResponse getTimeline(UUID encounterId) {
        Encounter encounter = encounterRepository.findByIdForRead(encounterId)
                .orElseThrow(() -> new EncounterNotFoundException(encounterId));
        List<EncounterLocation> locations = encounterLocationRepository
                .findAllByEncounter_IdOrderByStartedAtAscIdAsc(encounterId);
        List<EncounterDischarge> discharges = encounterDischargeRepository
                .findAllByEncounter_IdOrderByDischargedAtAscIdAsc(encounterId);
        return EncounterTimelineResponse.from(encounter, locations, discharges);
    }

    /** Returns discharge and correction audit for an existing encounter. */
    public List<EncounterDischargeResponse> getDischarges(UUID encounterId) {
        getEncounter(encounterId);
        return encounterDischargeRepository.findAllByEncounter_IdOrderByDischargedAtAscIdAsc(encounterId).stream()
                .map(EncounterDischargeResponse::from).toList();
    }

    /**
     * Returns an encounter without acquiring a workflow write lock.
     */
    public EncounterResponse getEncounter(UUID id) {
        return EncounterResponse.from(encounterRepository.findById(id)
                .orElseThrow(() -> new EncounterNotFoundException(id)));
    }

    /**
     * Lists all encounters for an existing patient, including cancelled admissions.
     */
    public EncounterPageResponse getPatientEncounters(UUID patientId, int page, int size) {
        patientService.getPatient(patientId);
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "admittedAt", "encounterNumber"));
        return EncounterPageResponse.from(encounterRepository.findAllByPatient_Id(patientId, pageable));
    }

}
