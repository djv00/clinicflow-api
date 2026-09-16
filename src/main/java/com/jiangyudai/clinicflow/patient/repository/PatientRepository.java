package com.jiangyudai.clinicflow.patient.repository;

import com.jiangyudai.clinicflow.patient.entity.Patient;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    boolean existsByMedicalRecordNumber(String medicalRecordNumber);

    @Query("""
            select p from Patient p
            where lower(p.medicalRecordNumber) like lower(:pattern) escape '!'
               or lower(concat(p.firstName, ' ', p.lastName)) like lower(:pattern) escape '!'
            """)
    Page<Patient> search(@Param("pattern") String pattern, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Patient p where p.id = :id")
    Optional<Patient> findByIdForUpdate(@Param("id") UUID id);
}
