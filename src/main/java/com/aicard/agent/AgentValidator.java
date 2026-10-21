package com.aicard.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * reflect 阶段：**确定性校验**（纯函数，非 LLM）。
 * <p>按 workBuddy spike 的结论——数字/格式用纯函数校验，LLM 只做修复提案。
 */
@Component
public class AgentValidator {

    private final ObjectMapper mapper;

    public AgentValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 返回 {@code null} 表示通过；否则返回失败原因（触发重试）。 */
    public String validate(List<StepRecord> history, String tool, ToolResult result) {
        if (result == null) {
            return "空结果";
        }
        if (result.artifact() != null) {
            if (result.artifact().length == 0) {
                return "产物为空字节";
            }
        } else if (result.output() == null || result.output().isBlank()) {
            return "输出为空";
        }

        // 跨步对账：生成步骤报告的数字，必须与前面的清单/汇总步骤一致
        if ("generate_report_docx".equals(tool)) {
            Integer expected = intFromTool(history, "list_maintenance_orders", "count");
            Integer got = intFromOutput(result.output(), "openOrders");
            if (expected != null && got != null && !expected.equals(got)) {
                return "対账失敗：openOrders=" + got + "、但し修繕清单=" + expected;
            }
        }
        if ("generate_usage_xlsx".equals(tool)) {
            Integer expected = intFromTool(history, "usage_summary", "turnCount");
            Integer got = intFromOutput(result.output(), "turnCount");
            if (expected != null && got != null && !expected.equals(got)) {
                return "対账失敗：turnCount=" + got + "、但し集計=" + expected;
            }
        }
        return null;
    }

    /**
     * 目标是否要求交付文件（用于消灭「假完成」：模型说完成但没产出文件）。
     * 只按显式的文书/表格措辞判断，避免把普通问答误判为需要文件。
     */
    public static boolean expectsArtifact(String goal) {
        if (goal == null) {
            return false;
        }
        String[] markers = {"作って", "作成", "レポート", "報告書", "Excel", "エクセル",
                "文書", "通知", "回覧", "下書き", "ドラフト"};
        for (String marker : markers) {
            if (goal.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private Integer intFromTool(List<StepRecord> history, String tool, String field) {
        for (StepRecord s : history) {
            if (tool.equals(s.tool()) && StepRecord.OK.equals(s.status())) {
                return intFromOutput(s.output(), field);
            }
        }
        return null;
    }

    private Integer intFromOutput(String outputJson, String field) {
        if (outputJson == null || outputJson.isBlank()) {
            return null;
        }
        try {
            var node = mapper.readTree(outputJson).path(field);
            return node.isMissingNode() ? null : node.asInt();
        } catch (Exception e) {
            return null;
        }
    }
}
