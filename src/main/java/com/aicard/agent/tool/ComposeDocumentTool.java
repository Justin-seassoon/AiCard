package com.aicard.agent.tool;

import com.aicard.agent.AgentArgs;
import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTool;
import com.aicard.agent.ToolResult;
import com.aicard.docgen.template.DocTemplate;
import com.aicard.docgen.template.TemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：按模板生成社内文书（通知 / 回覧 / 報告 / 議事録 → docx）。
 * 帳票（見積書/請求書）需要明細，建议从后台 UI 生成。
 */
@Component
public class ComposeDocumentTool implements AgentTool {

    private final TemplateService templates;
    private final ObjectMapper mapper;

    public ComposeDocumentTool(TemplateService templates, ObjectMapper mapper) {
        this.templates = templates;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "compose_document";
    }

    @Override
    public String description() {
        return "テンプレートで社内文書（通知/回覧/報告/議事録）を生成。args: template, topic, points";
    }

    @Override
    public String argsHint() {
        return "{\"template\":\"notice|circular|business_report|minutes\",\"topic\":\"string\",\"points\":\"string\"}";
    }

    @Override
    public List<String> requiredArgs() {
        return List.of("topic");
    }

    @Override
    public ToolResult run(AgentContext ctx, String argsJson) {
        JsonNode args = AgentArgs.parse(argsJson);
        String templateId = AgentArgs.text(args, "template", DocTemplate.NOTICE.id());
        if (docTemplate(templateId) == null) {
            throw new IllegalArgumentException("帳票（見積書/請求書）は明細が必要なため、后台 UI から作成してください");
        }
        Map<String, Object> fields = new HashMap<>();
        fields.put("件名", AgentArgs.text(args, "topic", ""));
        fields.put("要点", AgentArgs.text(args, "points", ""));

        TemplateService.Generated g = templates.generate(templateId, fields);
        ObjectNode out = mapper.createObjectNode();
        out.put("template", templateId);
        out.put("bytes", g.bytes().length);
        return ToolResult.artifact(out.toString(), g.bytes(), g.filename(), g.mime());
    }

    private static DocTemplate docTemplate(String id) {
        return DocTemplate.byId(id).filter(t -> !t.isSpreadsheet()).orElse(null);
    }
}
