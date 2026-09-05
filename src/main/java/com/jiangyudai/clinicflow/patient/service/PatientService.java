package com.jiangyudai.clinicflow.patient.service;

import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.exception.DuplicateMedicalRecordNumberException;
import com.jiangyudai.clinicflow.patient.exception.PatientNotFoundException;
import com.jiangyudai.clinicflow.patient.repository.PatientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Applies patient registration rules before patient data is stored.
 *
 * @author Jiangyu Dai
 */
@Service
@Transactional(readOnly = true)
public class PatientService {

    private final PatientRepository patientRepository;

    public PatientService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    /**
     * Registers a patient after checking the medical record number.
     */
    @Transactional
    public Patient createPatient(
            String medicalRecordNumber,
            String firstName,
            String lastName,
            LocalDate dateOfBirth
    ) {
        if (patientRepository.existsByMedicalRecordNumber(medicalRecordNumber)) {
            throw new DuplicateMedicalRecordNumberException(
                    medicalRecordNumber
            );
        }

        Patient patient = new Patient(
                medicalRecordNumber,
                firstName,
                lastName,
                dateOfBirth
        );

        return patientRepository.save(patient);
    }

    /**
     * Returns a patient or reports that the supplied identifier is unknown.
     */
    public Patient getPatient(UUID id) {
        return patientRepository.findById(id)
                .orElseThrow(() -> new PatientNotFoundException(id));
    }
}
