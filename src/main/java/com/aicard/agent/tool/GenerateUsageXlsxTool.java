package com.aicard.agent.tool;

import com.aicard.agent.AgentArgs;
import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTool;
import com.aicard.agent.ToolResult;
import com.aicard.docgen.ReportXlsxWriter;
import com.aicard.report.repository.DeviceAggregate;
import com.aicard.report.service.ReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 工具：生成「利用レポート」xlsx（**确定性**，数据 → 表格，无 LLM）。
 * 重新聚合一次（幂等），输出的 {@code turnCount} 会被 reflect 用于**与 usage_summary 对账**。
 */
@Component
public class GenerateUsageXlsxTool implements AgentTool {

    public static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReportService reports;
    private final ReportXlsxWriter xlsx;
    private final ObjectMapper mapper;

    public GenerateUsageXlsxTool(ReportService reports, ReportXlsxWriter xlsx, ObjectMapper mapper) {
        this.reports = reports;
        this.xlsx = xlsx;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "generate_usage_xlsx";
    }

    @Override
    public String description() {
        return "利用レポート（xlsx）を生成。args: days（既定 7）";
    }

    @Override
    public ToolResult run(AgentContext ctx, String argsJson) {
        int days = AgentArgs.intVal(AgentArgs.parse(argsJson), "days", 7);
        LocalDate end = LocalDate.now().plusDays(1);
        LocalDate start = end.minusDays(days);
        DeviceAggregate agg = reports.aggregate(ctx.customerId(), ctx.storeId(), null, start, end);
        byte[] bytes = xlsx.write("利用レポート", "店舗全体", start + " ～ " + end, agg);

        ObjectNode out = mapper.createObjectNode();
        out.put("turnCount", agg.turnCount());
        out.put("bytes", bytes.length);
        return ToolResult.artifact(out.toString(), bytes, "usage-report.xlsx", MIME_XLSX);
    }
}
