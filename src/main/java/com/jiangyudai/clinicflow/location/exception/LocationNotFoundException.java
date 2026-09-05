package com.jiangyudai.clinicflow.location.exception;

import java.util.UUID;

public class LocationNotFoundException extends RuntimeException {

    public LocationNotFoundException(String locationType, UUID id) {
        super(locationType + " not found: " + id);
    }
}