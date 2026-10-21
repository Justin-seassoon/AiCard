package com.aicard.agent.tool;

import com.aicard.agent.AgentArgs;
import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTool;
import com.aicard.agent.ToolResult;
import com.aicard.report.repository.DeviceAggregate;
import com.aicard.report.service.ReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 工具：租户级用量聚合（C4：deviceId=null）。 */
@Component
public class UsageSummaryTool implements AgentTool {

    private final ReportService reports;
    private final ObjectMapper mapper;

    public UsageSummaryTool(ReportService reports, ObjectMapper mapper) {
        this.reports = reports;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "usage_summary";
    }

    @Override
    public String description() {
        return "直近 N 日（既定 7 日）の店舗全体の利用実績を集計（翻訳/QA/稼働日）";
    }

    @Override
    public String argsHint() {
        return "{\"days\":\"int (optional, default 7)\"}";
    }

    @Override
    public ToolResult run(AgentContext ctx, String argsJson) {
        int days = AgentArgs.intVal(AgentArgs.parse(argsJson), "days", 7);
        LocalDate end = LocalDate.now().plusDays(1);
        LocalDate start = end.minusDays(days);
        DeviceAggregate agg = reports.aggregate(ctx.customerId(), ctx.storeId(), null, start, end);
        ObjectNode root = mapper.createObjectNode();
        root.put("turnCount", agg.turnCount());
        root.put("sessionCount", agg.sessionCount());
        root.put("qaHitCount", agg.qaHitCount());
        root.put("activeDays", agg.activeDays());
        root.put("periodStart", start.toString());
        root.put("periodEnd", end.toString());
        return ToolResult.of(root.toString());
    }
}
