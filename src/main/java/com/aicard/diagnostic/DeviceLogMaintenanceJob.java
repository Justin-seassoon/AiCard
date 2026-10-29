package com.aicard.diagnostic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 设备诊断日志到期清理。 */
@Component
public class DeviceLogMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(DeviceLogMaintenanceJob.class);

    private final DeviceLogService logs;

    public DeviceLogMaintenanceJob(DeviceLogService logs) {
        this.logs = logs;
    }

    @Scheduled(cron = "${device-log.cleanup-cron:0 40 3 * * *}")
    public void cleanupExpired() {
        int n = logs.cleanupExpired();
        if (n > 0) {
            log.info("device logs expired & deleted: {}", n);
        }
    }
}
