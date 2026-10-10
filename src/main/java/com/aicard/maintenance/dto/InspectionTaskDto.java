package com.aicard.maintenance.dto;

import com.aicard.maintenance.domain.InspectionTask;

import java.time.Instant;
import java.time.LocalDate;

public record InspectionTaskDto(Long id, String taskNo, String deviceId, String title, String checklist,
                                String status, LocalDate scheduledDate, String assignee, Instant createdAt) {
    public static InspectionTaskDto from(InspectionTask t) {
        return new InspectionTaskDto(t.getId(), t.getTaskNo(), t.getDeviceId(), t.getTitle(), t.getChecklist(),
                t.getStatus(), t.getScheduledDate(), t.getAssignee(), t.getCreatedAt());
    }
}
