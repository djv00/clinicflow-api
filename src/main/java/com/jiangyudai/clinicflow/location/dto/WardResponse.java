package com.jiangyudai.clinicflow.location.dto;

import com.jiangyudai.clinicflow.location.entity.Ward;

import java.util.UUID;

public record WardResponse(UUID id, String wardCode, String wardName, boolean active) {

    public static WardResponse from(Ward ward) {
        return new WardResponse(ward.getId(), ward.getWardCode(), ward.getWardName(), ward.isActive());
    }
}
