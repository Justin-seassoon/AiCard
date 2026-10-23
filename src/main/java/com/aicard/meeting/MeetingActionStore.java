package com.aicard.meeting;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 会议待办落库（周报的真实进度来源）。 */
@Service
public class MeetingActionStore {

    private final MeetingActionItemRepository repo;

    public MeetingActionStore(MeetingActionItemRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void replace(Meeting meeting, List<MinutesService.ActionDraft> drafts) {
        repo.deleteByMeetingId(meeting.getMeetingId());
        Instant now = Instant.now();
        int order = 0;
        for (MinutesService.ActionDraft d : drafts) {
            if (d.task() == null || d.task().isBlank()) {
                continue;
            }
            repo.save(MeetingActionItem.builder()
                    .meetingId(meeting.getMeetingId())
                    .customerId(meeting.getCustomerId())
                    .storeId(meeting.getStoreId())
                    .task(d.task())
                    .owner(d.owner())
                    .dueDate(d.dueDate())
                    .status(MeetingActionItem.STATUS_TODO)
                    .sourceRef(d.sourceRef())
                    .sortOrder(order++)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
    }

    public List<MeetingActionItem> list(String meetingId) {
        return repo.findByMeetingIdOrderBySortOrderAsc(meetingId);
    }
}
