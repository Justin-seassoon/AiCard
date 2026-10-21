package com.aicard.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentValidatorTest {

    private final AgentValidator validator = new AgentValidator(new ObjectMapper());

    @Test
    void passesNonEmptyArtifact() {
        ToolResult r = ToolResult.artifact("{\"openOrders\":3}", new byte[]{1, 2}, "x.docx", "m");
        assertThat(validator.validate(List.of(), "generate_report_docx", r)).isNull();
    }

    @Test
    void rejectsEmptyArtifact() {
        ToolResult r = ToolResult.artifact("{}", new byte[0], "x.docx", "m");
        assertThat(validator.validate(List.of(), "generate_report_docx", r)).contains("产物为空");
    }

    @Test
    void rejectsEmptyOutput() {
        assertThat(validator.validate(List.of(), "usage_summary", ToolResult.of("  "))).contains("输出为空");
    }

    @Test
    void detectsArtifactExpectation() {
        assertThat(AgentValidator.expectsArtifact("レポートを作って")).isTrue();
        assertThat(AgentValidator.expectsArtifact("修繕オーダーの一覧を Excel にして")).isTrue();
        assertThat(AgentValidator.expectsArtifact("未回答の質問から社内通知を作って")).isTrue();
        assertThat(AgentValidator.expectsArtifact("今週の翻訳件数は？")).isFalse();
        assertThat(AgentValidator.expectsArtifact("何ができる？")).isFalse();
        assertThat(AgentValidator.expectsArtifact(null)).isFalse();
    }

    @Test
    void reconcilesReportCountsAcrossSteps() {        StepRecord listed = new StepRecord("list_maintenance_orders", "{}", StepRecord.OK,
                "{\"count\":3}", null, 1, 1);
        List<StepRecord> history = List.of(listed);

        ToolResult mismatch = ToolResult.artifact("{\"openOrders\":2}", new byte[]{1}, "x.docx", "m");
        assertThat(validator.validate(history, "generate_report_docx", mismatch)).contains("失敗");

        ToolResult match = ToolResult.artifact("{\"openOrders\":3}", new byte[]{1}, "x.docx", "m");
        assertThat(validator.validate(history, "generate_report_docx", match)).isNull();
    }
}
