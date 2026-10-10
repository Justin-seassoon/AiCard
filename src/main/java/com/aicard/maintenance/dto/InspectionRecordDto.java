package com.aicard.maintenance.dto;

import com.aicard.maintenance.domain.InspectionRecord;

import java.time.Instant;

public record InspectionRecordDto(Long id, Long taskId, String deviceId, String results, String status,
                                  String inspectedBy, Instant inspectedAt, String note, Instant createdAt) {
    public static InspectionRecordDto from(InspectionRecord r) {
        return new InspectionRecordDto(r.getId(), r.getTaskId(), r.getDeviceId(), r.getResults(), r.getStatus(),
                r.getInspectedBy(), r.getInspectedAt(), r.getNote(), r.getCreatedAt());
    }
}
