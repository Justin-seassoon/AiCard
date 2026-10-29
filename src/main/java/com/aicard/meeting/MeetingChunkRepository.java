package com.aicard.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MeetingChunkRepository extends JpaRepository<MeetingChunk, Long> {

    Optional<MeetingChunk> findByMeetingIdAndSeq(String meetingId, Integer seq);

    List<MeetingChunk> findByMeetingIdOrderBySeqAsc(String meetingId);

    long countByMeetingId(String meetingId);
}
