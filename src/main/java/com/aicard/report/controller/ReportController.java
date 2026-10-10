package com.aicard.report.controller;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import com.aicard.report.dto.ReportDto;
import com.aicard.report.dto.ReportRequest;
import com.aicard.report.service.ReportService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** 周报/月报后台 API。 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    public List<ReportDto> list(@RequestParam String periodType,
                                @RequestParam(required = false) LocalDate start,
                                @RequestParam(required = false) LocalDate end) {
        Tenant t = TenantContext.require();
        return reports.list(t.customerId(), t.storeId(), periodType, start, end)
                .stream().map(ReportDto::from).toList();
    }

    @GetMapping("/devices/{deviceId}")
    public ReportDto get(@PathVariable String deviceId,
                         @RequestParam String periodType,
                         @RequestParam LocalDate start) {
        return reports.find(deviceId, periodType, start).map(ReportDto::from).orElse(null);
    }

    @PostMapping("/generate")
    public ReportDto generate(@RequestBody ReportRequest req) {
        Tenant t = TenantContext.require();
        return ReportDto.from(reports.generate(t.customerId(), t.storeId(), req.deviceId(),
                req.periodType(), req.start(), req.end()));
    }
}
