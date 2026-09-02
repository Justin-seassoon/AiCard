package com.aicard.common.repository;

import com.aicard.common.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreRepository extends JpaRepository<Store, Long> {
    List<Store> findByCustomerId(Long customerId);
}
