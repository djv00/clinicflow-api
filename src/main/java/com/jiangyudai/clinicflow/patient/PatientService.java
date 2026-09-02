package com.jiangyudai.clinicflow.patient;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PatientService {

    private final PatientRepository patientRepository;

    public PatientService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

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

    public Patient getPatient(UUID id) {
        return patientRepository.findById(id)
                .orElseThrow(() -> new PatientNotFoundException(id));
    }
}