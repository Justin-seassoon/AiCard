package com.aicard.translation.qualify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QualifyTermRepository extends JpaRepository<QualifyTerm, Long> {

    List<QualifyTerm> findByCustomerIdAndStoreIdAndType(Long customerId, Long storeId, String type);
}
