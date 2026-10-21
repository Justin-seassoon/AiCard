package com.aicard.agent.tool;

import com.aicard.agent.AgentArgs;
import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTool;
import com.aicard.agent.ToolResult;
import com.aicard.docgen.DocxRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具：把**模型写好的内容**渲染成 docx（工具只负责排版，不生成内容）。
 * args: {@code {title, lines[]}}。
 */
@Component
public class ComposeDocxTool implements AgentTool {

    private static final String MIME_DOCX = GenerateReportDocxTool.MIME_DOCX;
    private static final String NOTE = "※ 本ファイルは AI が作成した下書きです。提出前にご確認ください。";

    private final DocxRenderer renderer;
    private final ObjectMapper mapper;

    public ComposeDocxTool(DocxRenderer renderer, ObjectMapper mapper) {
        this.renderer = renderer;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "compose_docx";
    }

    @Override
    public String description() {
        return "社内文書（docx）を生成する。本文はあなたが作成し lines に渡す";
    }

    @Override
    public String argsHint() {
        return "{\"title\":\"string\",\"lines\":[\"string\"]}";
    }

    @Override
    public List<String> requiredArgs() {
        return List.of("title", "lines");
    }

    @Override
    public ToolResult run(AgentContext ctx, String argsJson) {
        JsonNode args = AgentArgs.parse(argsJson);
        String title = AgentArgs.text(args, "title", "社内文書");

        List<String> lines = new ArrayList<>();
        JsonNode linesNode = args.path("lines");
        if (linesNode.isArray()) {
            linesNode.forEach(n -> lines.add(n.asText("")));
        }
        byte[] docx = renderer.render(title, lines, NOTE);

        ObjectNode out = mapper.createObjectNode();
        out.put("title", title);
        out.put("lines", lines.size());
        out.put("bytes", docx.length);
        return ToolResult.artifact(out.toString(), docx, "composed-doc.docx", MIME_DOCX);
    }
}
