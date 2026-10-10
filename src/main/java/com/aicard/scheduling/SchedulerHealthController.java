package com.aicard.scheduling;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import com.aicard.common.service.RuntimeConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 调度状态查询：便于验证调度配置与 system_config 覆盖是否生效。 */
@RestController
@RequestMapping("/api/schedule")
public class SchedulerHealthController {

    private final RuntimeConfigService configs;
    private final String reportWeeklyCron;
    private final String reportMonthlyCron;
    private final long pushPollMs;

    public SchedulerHealthController(RuntimeConfigService configs,
                                     @Value("${app.schedule.report-weekly-cron:0 0 8 * * MON}") String reportWeeklyCron,
                                     @Value("${app.schedule.report-monthly-cron:0 0 8 1 * *}") String reportMonthlyCron,
                                     @Value("${app.schedule.push-poll-ms:30000}") long pushPollMs) {
        this.configs = configs;
        this.reportWeeklyCron = reportWeeklyCron;
        this.reportMonthlyCron = reportMonthlyCron;
        this.pushPollMs = pushPollMs;
    }

    @GetMapping("/status")
    public ScheduleStatus status() {
        Tenant t = TenantContext.require();
        return new ScheduleStatus(
                configs.get(t.customerId(), t.storeId(), "report.weekly.cron", reportWeeklyCron),
                configs.get(t.customerId(), t.storeId(), "report.monthly.cron", reportMonthlyCron),
                Long.parseLong(configs.get(t.customerId(), t.storeId(), "push.poll.ms", String.valueOf(pushPollMs))));
    }

    public record ScheduleStatus(String reportWeeklyCron, String reportMonthlyCron, long pushPollMs) {}
}
