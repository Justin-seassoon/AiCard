package com.aicard.report.job;

import com.aicard.report.domain.DeviceUsageReport;
import com.aicard.report.service.ReportService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 定时生成周报/月报。cron 默认值来自 app.schedule.*，可由 system_config 覆盖。 */
@Component
public class ReportGenerationJob {

    private final ReportService reports;

    public ReportGenerationJob(ReportService reports) {
        this.reports = reports;
    }

    @Scheduled(cron = "${app.schedule.report-weekly-cron:0 0 8 * * MON}")
    public void weekly() {
        LocalDate end = LocalDate.now();
        reports.generateDue(DeviceUsageReport.WEEKLY, end.minusWeeks(1), end);
    }

    @Scheduled(cron = "${app.schedule.report-monthly-cron:0 0 8 1 * *}")
    public void monthly() {
        LocalDate end = LocalDate.now();
        reports.generateDue(DeviceUsageReport.MONTHLY, end.minusMonths(1), end);
    }
}
