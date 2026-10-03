package com.jiangyudai.clinicflow.web.error;

import com.jiangyudai.clinicflow.encounter.exception.*;
import com.jiangyudai.clinicflow.location.exception.InvalidLocationException;
import com.jiangyudai.clinicflow.location.exception.LocationNotFoundException;
import com.jiangyudai.clinicflow.patient.exception.DuplicateMedicalRecordNumberException;
import com.jiangyudai.clinicflow.patient.exception.PatientNotFoundException;
import com.jiangyudai.clinicflow.physician.exception.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts business and validation failures into consistent API problem details.
 *
 * @author Jiangyu Dai
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidPhysicianAssignmentException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPhysicianAssignment(InvalidPhysicianAssignmentException exception) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PHYSICIAN_ASSIGNMENT,
                "Invalid physician assignment", exception.getMessage());
    }

    @ExceptionHandler(PhysicianAssignmentConflictException.class)
    public ResponseEntity<ProblemDetail> handlePhysicianAssignmentConflict(PhysicianAssignmentConflictException exception) {
        return problem(HttpStatus.CONFLICT, ApiErrorCode.PHYSICIAN_ASSIGNMENT_CONFLICT,
                "Physician assignment conflict", exception.getMessage());
    }

    @ExceptionHandler(PhysicianNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePhysicianNotFound(PhysicianNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, ApiErrorCode.PHYSICIAN_NOT_FOUND,
                "Physician not found", exception.getMessage());
    }

    @ExceptionHandler(DuplicatePhysicianCodeException.class)
    public ResponseEntity<ProblemDetail> handleDuplicatePhysicianCode(DuplicatePhysicianCodeException exception) {
        return problem(HttpStatus.CONFLICT, ApiErrorCode.DUPLICATE_PHYSICIAN_CODE,
                "Duplicate physician code", exception.getMessage());
    }

    @ExceptionHandler({PhysicianVersionConflictException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<ProblemDetail> handleConcurrentUpdate(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, ApiErrorCode.UPDATE_CONFLICT,
                "Update conflict", "The record has changed. Reload it before saving.");
    }

    @ExceptionHandler(InvalidPhysicianDepartmentException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPhysicianDepartment(InvalidPhysicianDepartmentException exception) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PHYSICIAN_DEPARTMENT,
                "Invalid physician department", exception.getMessage());
    }

    // Encounter admission errors
    @ExceptionHandler(DuplicateEncounterNumberException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateEncounterNumber(
            DuplicateEncounterNumberException exception
    ) {
        return problem(HttpStatus.CONFLICT, ApiErrorCode.DUPLICATE_ENCOUNTER_NUMBER,
                "Duplicate encounter number", exception.getMessage());
    }

    @ExceptionHandler(ActiveEncounterExistsException.class)
    public ResponseEntity<ProblemDetail> handleActiveEncounterExists(
            ActiveEncounterExistsException exception
    ) {
        return problem(HttpStatus.CONFLICT, ApiErrorCode.ACTIVE_ENCOUNTER_EXISTS,
                "Active encounter already exists", exception.getMessage());
    }

    @ExceptionHandler({EncounterHistoryConflictException.class, BedHistoryConflictException.class,
            SubsequentEncounterExistsException.class})
    public ResponseEntity<ProblemDetail> handleEncounterHistoryConflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, exception instanceof BedHistoryConflictException ? ApiErrorCode.BED_HISTORY_CONFLICT
                : exception instanceof SubsequentEncounterExistsException ? ApiErrorCode.SUBSEQUENT_ENCOUNTER_EXISTS
                : ApiErrorCode.ENCOUNTER_HISTORY_CONFLICT,
                "Encounter history conflict", exception.getMessage());
    }

    @ExceptionHandler(EncounterNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleEncounterNotFound(
            EncounterNotFoundException exception
    ) {
        return problem(HttpStatus.NOT_FOUND, ApiErrorCode.ENCOUNTER_NOT_FOUND,
                "Encounter not found", exception.getMessage());
    }

    @ExceptionHandler(InvalidAdmissionTimeException.class)
    public ResponseEntity<ProblemDetail> handleInvalidAdmissionTime(
            InvalidAdmissionTimeException exception
    ) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_ADMISSION_TIME,
                "Invalid admission time", exception.getMessage());
    }

    @ExceptionHandler(InvalidDischargeTimeException.class)
    public ResponseEntity<ProblemDetail> handleInvalidDischargeTime(
            InvalidDischargeTimeException exception
    ) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_DISCHARGE_TIME,
                "Invalid discharge time", exception.getMessage());
    }

    @ExceptionHandler(InvalidAdmissionCancellationException.class)
    public ResponseEntity<ProblemDetail> handleInvalidAdmissionCancellation(
            InvalidAdmissionCancellationException exception
    ) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_ADMISSION_CANCELLATION,
                "Invalid admission cancellation", exception.getMessage());
    }

    @ExceptionHandler(InvalidDischargeCancellationException.class)
    public ResponseEntity<ProblemDetail> handleInvalidDischargeCancellation(
            InvalidDischargeCancellationException exception
    ) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_DISCHARGE_CANCELLATION,
                "Invalid discharge cancellation", exception.getMessage());
    }

    // Patient registration errors
    @ExceptionHandler(DuplicateMedicalRecordNumberException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateMedicalRecordNumber(
            DuplicateMedicalRecordNumberException exception
    ) {
        return problem(HttpStatus.CONFLICT, ApiErrorCode.DUPLICATE_MEDICAL_RECORD_NUMBER,
                "Duplicate medical record number", exception.getMessage());
    }

    @ExceptionHandler(PatientNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePatientNotFound(
            PatientNotFoundException exception
    ) {
        return problem(HttpStatus.NOT_FOUND, ApiErrorCode.PATIENT_NOT_FOUND,
                "Patient not found", exception.getMessage());
    }

    // Request validation errors
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> errors = new LinkedHashMap<>();

        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed"
        );
        problem.setTitle("Invalid request");
        problem.setProperty("code", ApiErrorCode.INVALID_REQUEST.name());
        problem.setProperty("errors", errors);

        return ResponseEntity.badRequest().body(problem);
    }

    // Encounter location workflow errors
    @ExceptionHandler({
            BedOccupiedException.class,
            ActiveEncounterLocationExistsException.class,
            CurrentEncounterLocationNotFoundException.class,
            EncounterLocationAlreadyEndedException.class,
            EncounterLocationHistoryExistsException.class,
            EncounterLocationChangedException.class,
            DischargeRecordConflictException.class,
            SameEncounterLocationException.class,
            InvalidEncounterStatusException.class
    })
    public ResponseEntity<ProblemDetail> handleEncounterLocationConflict(
            RuntimeException exception
    ) {
        return problem(HttpStatus.CONFLICT, exception instanceof BedOccupiedException ? ApiErrorCode.BED_OCCUPIED
                : ApiErrorCode.ENCOUNTER_LOCATION_CONFLICT,
                "Encounter location conflict", exception.getMessage());
    }

    @ExceptionHandler({
            InvalidDepartmentAdmissionTimeException.class,
            InvalidEncounterTransferTimeException.class,
            InvalidEncounterLocationTimeException.class,
            InvalidLocationException.class
    })
    public ResponseEntity<ProblemDetail> handleInvalidEncounterLocation(
            RuntimeException exception
    ) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_LOCATION,
                "Invalid encounter location", exception.getMessage());
    }

    @ExceptionHandler(LocationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleLocationNotFound(
            LocationNotFoundException exception
    ) {
        return problem(HttpStatus.NOT_FOUND, ApiErrorCode.LOCATION_NOT_FOUND,
                "Location not found", exception.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, ApiErrorCode code,
                                                         String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code.name());
        return ResponseEntity.status(status).body(problem);
    }
}
