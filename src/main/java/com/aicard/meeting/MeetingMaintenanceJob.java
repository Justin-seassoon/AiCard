package com.aicard.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 会议兜底任务：超时自动结束 + 到期删除音频（保留转写与纪要）。 */
@Component
public class MeetingMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(MeetingMaintenanceJob.class);

    private final MeetingService meetings;

    public MeetingMaintenanceJob(MeetingService meetings) {
        this.meetings = meetings;
    }

    @Scheduled(fixedDelayString = "${meeting.finalize-scan-ms:300000}")
    public void finalizeStale() {
        int n = meetings.finalizeStale();
        if (n > 0) {
            log.info("meeting auto-finalized: {}", n);
        }
    }

    @Scheduled(cron = "${meeting.cleanup-cron:0 20 3 * * *}")
    public void cleanupExpiredAudio() {
        int n = meetings.cleanupExpiredAudio();
        if (n > 0) {
            log.info("meeting audio expired & deleted: {}", n);
        }
    }
}
