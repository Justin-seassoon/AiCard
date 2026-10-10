package com.aicard.notification.job;

import com.aicard.common.repository.DeviceRepository;
import com.aicard.notification.service.NotificationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时轮询到期运营通知并派发。轮询周期默认 30s，可由 system_config 覆盖。 */
@Component
public class NotificationPollingJob {

    private final NotificationService notifications;
    private final DeviceRepository devices;

    public NotificationPollingJob(NotificationService notifications, DeviceRepository devices) {
        this.notifications = notifications;
        this.devices = devices;
    }

    @Scheduled(fixedDelayString = "${app.schedule.push-poll-ms:30000}")
    public void poll() {
        for (Object[] pair : devices.findDistinctTenantPairs()) {
            notifications.dispatchDue((Long) pair[0], (Long) pair[1]);
        }
    }
}
