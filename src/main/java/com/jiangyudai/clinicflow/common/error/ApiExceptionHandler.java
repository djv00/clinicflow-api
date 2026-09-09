package com.jiangyudai.clinicflow.common.error;

import com.jiangyudai.clinicflow.encounter.exception.*;
import com.jiangyudai.clinicflow.location.exception.InvalidLocationException;
import com.jiangyudai.clinicflow.location.exception.LocationNotFoundException;
import com.jiangyudai.clinicflow.patient.exception.DuplicateMedicalRecordNumberException;
import com.jiangyudai.clinicflow.patient.exception.PatientNotFoundException;
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

    // Encounter admission errors
    @ExceptionHandler(DuplicateEncounterNumberException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateEncounterNumber(
            DuplicateEncounterNumberException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );
        problem.setTitle("Duplicate encounter number");

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(ActiveEncounterExistsException.class)
    public ResponseEntity<ProblemDetail> handleActiveEncounterExists(
            ActiveEncounterExistsException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );
        problem.setTitle("Active encounter already exists");

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler({EncounterHistoryConflictException.class, BedHistoryConflictException.class,
            SubsequentEncounterExistsException.class})
    public ResponseEntity<ProblemDetail> handleEncounterHistoryConflict(RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Encounter history conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(EncounterNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleEncounterNotFound(
            EncounterNotFoundException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );
        problem.setTitle("Encounter not found");

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(InvalidAdmissionTimeException.class)
    public ResponseEntity<ProblemDetail> handleInvalidAdmissionTime(
            InvalidAdmissionTimeException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                exception.getMessage()
        );
        problem.setTitle("Invalid admission time");

        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(InvalidDischargeTimeException.class)
    public ResponseEntity<ProblemDetail> handleInvalidDischargeTime(
            InvalidDischargeTimeException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                exception.getMessage()
        );
        problem.setTitle("Invalid discharge time");

        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(InvalidAdmissionCancellationException.class)
    public ResponseEntity<ProblemDetail> handleInvalidAdmissionCancellation(
            InvalidAdmissionCancellationException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                exception.getMessage()
        );
        problem.setTitle("Invalid admission cancellation");

        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(InvalidDischargeCancellationException.class)
    public ResponseEntity<ProblemDetail> handleInvalidDischargeCancellation(
            InvalidDischargeCancellationException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid discharge cancellation");
        return ResponseEntity.badRequest().body(problem);
    }

    // Patient registration errors
    @ExceptionHandler(DuplicateMedicalRecordNumberException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateMedicalRecordNumber(
            DuplicateMedicalRecordNumberException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );
        problem.setTitle("Duplicate medical record number");

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(PatientNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePatientNotFound(
            PatientNotFoundException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );
        problem.setTitle("Patient not found");

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
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
            DischargeRecordConflictException.class,
            SameEncounterLocationException.class,
            InvalidEncounterStatusException.class
    })
    public ResponseEntity<ProblemDetail> handleEncounterLocationConflict(
            RuntimeException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );
        problem.setTitle("Encounter location conflict");

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
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
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                exception.getMessage()
        );
        problem.setTitle("Invalid encounter location");

        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(LocationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleLocationNotFound(
            LocationNotFoundException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );
        problem.setTitle("Location not found");

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }
}
