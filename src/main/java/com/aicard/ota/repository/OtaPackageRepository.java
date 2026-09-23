package com.aicard.ota.repository;

import com.aicard.ota.domain.OtaPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OtaPackageRepository extends JpaRepository<OtaPackage, Long> {
    List<OtaPackage> findByReleaseIdOrderBySortOrderAsc(Long releaseId);
}
