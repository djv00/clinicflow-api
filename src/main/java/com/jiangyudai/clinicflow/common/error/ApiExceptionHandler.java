package com.jiangyudai.clinicflow.common.error;

import com.jiangyudai.clinicflow.encounter.exception.ActiveEncounterExistsException;
import com.jiangyudai.clinicflow.encounter.exception.DuplicateEncounterNumberException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterNotFoundException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidAdmissionTimeException;
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

@RestControllerAdvice
public class ApiExceptionHandler {

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
}
