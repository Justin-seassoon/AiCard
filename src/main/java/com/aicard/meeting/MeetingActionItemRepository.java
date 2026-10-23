package com.aicard.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MeetingActionItemRepository extends JpaRepository<MeetingActionItem, Long> {

    List<MeetingActionItem> findByMeetingIdOrderBySortOrderAsc(String meetingId);

    List<MeetingActionItem> findByCustomerIdAndStoreIdAndStatus(Long customerId, Long storeId, String status);

    void deleteByMeetingId(String meetingId);
}
