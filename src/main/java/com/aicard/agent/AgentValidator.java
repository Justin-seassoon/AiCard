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
