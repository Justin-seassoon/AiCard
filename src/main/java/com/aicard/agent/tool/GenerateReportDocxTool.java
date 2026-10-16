package com.aicard.agent.tool;

import com.aicard.agent.AgentArgs;
import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTool;
import com.aicard.agent.ToolResult;
import com.aicard.docgen.DocxRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具：生成「業務アラート報告」docx（**确定性**，数据 → 文书，无 LLM）。
 * 输出的 {@code openOrders}/{@code inspectionIssues} 会被 reflect 用于**跨步对账**。
 */
@Component
public class GenerateReportDocxTool implements AgentTool {

    public static final String MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String NOTE = "※ 本ファイルは AI が作成した下書きです。提出前にご確認ください。";

    private final DocxRenderer renderer;
    private final ObjectMapper mapper;

    public GenerateReportDocxTool(DocxRenderer renderer, ObjectMapper mapper) {
        this.renderer = renderer;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "generate_report_docx";
    }

    @Override
    public String description() {
        return "業務アラート報告書（docx）を生成。args: openOrders, inspectionIssues";
    }

    @Override
    public ToolResult run(AgentContext ctx, String argsJson) {
        var args = AgentArgs.parse(argsJson);
        int openOrders = AgentArgs.intVal(args, "openOrders", 0);
        int inspections = AgentArgs.intVal(args, "inspectionIssues", 0);

        List<String> lines = List.of(
                "本日のアラート状況を報告します。",
                "・未対応の修繕オーダー：" + openOrders + " 件",
                "・点検タスク：" + inspections + " 件",
                "合計 " + (openOrders + inspections) + " 件を確認してください。");

        byte[] docx = renderer.render("業務アラート報告", lines, NOTE);

        ObjectNode out = mapper.createObjectNode();
        out.put("openOrders", openOrders);
        out.put("inspectionIssues", inspections);
        out.put("bytes", docx.length);
        return ToolResult.artifact(out.toString(), docx, "alert-report.docx", MIME_DOCX);
    }
}
