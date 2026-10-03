package com.jiangyudai.clinicflow.patient.service;

import com.jiangyudai.clinicflow.patient.dto.PatientResponse;
import com.jiangyudai.clinicflow.patient.dto.PatientPageResponse;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.exception.DuplicateMedicalRecordNumberException;
import com.jiangyudai.clinicflow.patient.exception.PatientNotFoundException;
import com.jiangyudai.clinicflow.patient.repository.PatientRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Handles patient registration and lookup.
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
    public PatientResponse createPatient(
            String medicalRecordNumber,
            String firstName,
            String lastName,
            LocalDate dateOfBirth
    ) {
        medicalRecordNumber = medicalRecordNumber.trim();
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

        try {
            return PatientResponse.from(patientRepository.saveAndFlush(patient));
        } catch (DataIntegrityViolationException exception) {
            // Concurrent registrations can both pass the existence check.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uk_patients_medical_record_number".equalsIgnoreCase(violation.getConstraintName())) {
                    throw new DuplicateMedicalRecordNumberException(medicalRecordNumber);
                }
            }
            throw exception;
        }
    }

    /**
     * Returns a patient or reports that the supplied identifier is unknown.
     */
    public PatientResponse getPatient(UUID id) {
        return PatientResponse.from(patientRepository.findById(id)
                .orElseThrow(() -> new PatientNotFoundException(id)));
    }

    /**
     * Finds patients by a literal name or medical record number fragment.
     */
    public PatientPageResponse searchPatients(String keyword, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by("lastName", "firstName", "medicalRecordNumber"));
        String search = keyword == null ? "" : keyword.strip();
        if (search.isEmpty()) {
            return PatientPageResponse.from(patientRepository.findAll(pageable));
        }

        // Escape LIKE metacharacters so user input remains a literal fragment.
        String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return PatientPageResponse.from(patientRepository.search(pattern, pageable));
    }

    /**
     * Serializes workflows that can open an active encounter for this patient.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Patient getPatientForUpdate(UUID id) {
        return patientRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new PatientNotFoundException(id));
    }
}
