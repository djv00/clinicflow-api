package com.jiangyudai.clinicflow.patient.repository;

import com.jiangyudai.clinicflow.patient.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    boolean existsByMedicalRecordNumber(String medicalRecordNumber);
}
