package com.aicard.common.service;

import com.aicard.common.domain.SystemConfig;
import com.aicard.common.repository.SystemConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeConfigServiceTest {

    private final SystemConfigRepository configs = mock(SystemConfigRepository.class);
    private final RuntimeConfigService service = new RuntimeConfigService(configs);

    private SystemConfig cfg(Long customerId, Long storeId, String value) {
        return SystemConfig.builder().configKey("report.weekly.cron")
                .configValue(value).customerId(customerId).storeId(storeId).build();
    }

    @Test
    void returnsFirstOverrideInPriorityOrder() {
        when(configs.findOverrides(10L, 20L, "report.weekly.cron")).thenReturn(List.of(
                cfg(10L, 20L, "store-cron"), cfg(10L, null, "customer-cron"), cfg(null, null, "global-cron")));

        assertThat(service.get(10L, 20L, "report.weekly.cron", "default")).isEqualTo("store-cron");
    }

    @Test
    void fallsBackToDefaultWhenNoOverride() {
        when(configs.findOverrides(10L, 20L, "report.weekly.cron")).thenReturn(List.of());

        assertThat(service.get(10L, 20L, "report.weekly.cron", "default")).isEqualTo("default");
    }
}
