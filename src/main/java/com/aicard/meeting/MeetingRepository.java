package com.aicard.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    Optional<Meeting> findByMeetingId(String meetingId);

    Optional<Meeting> findByMeetingIdAndCustomerIdAndStoreId(String meetingId, Long customerId, Long storeId);

    Optional<Meeting> findByDeviceIdAndClientKey(String deviceId, String clientKey);

    List<Meeting> findByCustomerIdAndStoreIdOrderByStartedAtDesc(Long customerId, Long storeId);

    List<Meeting> findByCustomerIdAndStoreIdAndStatusOrderByStartedAtDesc(Long customerId, Long storeId, String status);

    List<Meeting> findByStatusInAndUpdatedAtBefore(List<String> statuses, Instant threshold);

    List<Meeting> findByAudioExpiresAtBeforeAndAudioDeletedAtIsNull(Instant now);
}
