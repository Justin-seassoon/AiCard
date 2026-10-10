package com.aicard.common.repository;

import com.aicard.common.domain.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SystemConfigRepository extends JpaRepository<SystemConfig, Long> {

    /** 按 门店 → 租户 → 全局 的优先级返回覆盖链，调用方取第一条。 */
    @Query("""
            SELECT c FROM SystemConfig c
            WHERE c.configKey = :key
              AND ((c.customerId = :customerId AND c.storeId = :storeId)
                OR (c.customerId = :customerId AND c.storeId IS NULL)
                OR (c.customerId IS NULL AND c.storeId IS NULL))
            ORDER BY CASE
                WHEN c.customerId IS NOT NULL AND c.storeId IS NOT NULL THEN 0
                WHEN c.customerId IS NOT NULL THEN 1
                ELSE 2
            END
            """)
    List<SystemConfig> findOverrides(@Param("customerId") Long customerId,
                                     @Param("storeId") Long storeId,
                                     @Param("key") String key);
}
