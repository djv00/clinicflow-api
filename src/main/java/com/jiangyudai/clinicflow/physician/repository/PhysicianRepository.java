package com.jiangyudai.clinicflow.physician.repository;

import com.jiangyudai.clinicflow.physician.entity.Physician;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PhysicianRepository extends JpaRepository<Physician, UUID> {

    Optional<Physician> findByPhysicianCode(String physicianCode);

    boolean existsByPhysicianCode(String physicianCode);

    // Lock the root before loading affiliations; directory edits update its version before changing the join table.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Physician p where p.id = :id")
    Optional<Physician> findByIdForRead(@Param("id") UUID id);

    @EntityGraph(attributePaths = "departments")
    Optional<Physician> findWithDepartmentsById(UUID id);

    @EntityGraph(attributePaths = "departments")
    List<Physician> findWithDepartmentsByIdIn(Collection<UUID> ids);

    @Query("""
            select p from Physician p
            where (lower(p.physicianCode) like lower(:pattern) escape '!'
                or lower(concat(p.firstName, ' ', p.lastName)) like lower(:pattern) escape '!')
              and (:active is null or p.active = :active)
              and (:departmentId is null or exists (
                  select 1 from p.departments d where d.id = :departmentId))
            """)
    Page<Physician> search(@Param("pattern") String pattern, @Param("departmentId") UUID departmentId,
                           @Param("active") Boolean active, Pageable pageable);
}
