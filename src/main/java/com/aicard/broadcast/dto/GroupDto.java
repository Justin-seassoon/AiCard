package com.aicard.broadcast.dto;

import com.aicard.broadcast.domain.DeviceGroup;

public record GroupDto(Long id, String code, String name) {
    public static GroupDto from(DeviceGroup g) {
        return new GroupDto(g.getId(), g.getCode(), g.getName());
    }
}
