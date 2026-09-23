package com.aicard.ota.repository;

import com.aicard.ota.domain.OtaRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OtaReleaseRepository extends JpaRepository<OtaRelease, Long> {
    Optional<OtaRelease> findByStatus(String status);

    Optional<OtaRelease> findByUpdateId(String updateId);

    List<OtaRelease> findAllByOrderByCreatedAtDesc();
}
