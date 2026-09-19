package com.aicard.broadcast.repository;

import com.aicard.broadcast.domain.Broadcast;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {

    List<Broadcast> findByGroupIdOrderByCreatedAtDesc(Long groupId);
}
