package com.aicard.common.service;

import com.aicard.common.domain.SystemConfig;
import com.aicard.common.repository.SystemConfigRepository;
import org.springframework.stereotype.Service;

/** 运行时配置：system_config 表覆盖 + yml 默认值兜底，按 门店 → 租户 → 全局 覆盖链解析。 */
@Service
public class RuntimeConfigService {

    private final SystemConfigRepository configs;

    public RuntimeConfigService(SystemConfigRepository configs) {
        this.configs = configs;
    }

    /** 按覆盖链取配置值，无命中返回 defaultValue。 */
    public String get(Long customerId, Long storeId, String key, String defaultValue) {
        return configs.findOverrides(customerId, storeId, key).stream()
                .findFirst().map(SystemConfig::getConfigValue).orElse(defaultValue);
    }
}
