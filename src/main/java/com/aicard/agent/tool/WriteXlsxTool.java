package com.aicard.agent.tool;

import com.aicard.agent.AgentArgs;
import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTool;
import com.aicard.agent.ToolResult;
import com.aicard.docgen.SimpleXlsxWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具：把表数据渲染成 xlsx（工具只负责排版）。
 * args: {@code {title, columns[], rows[][]}}。
 */
@Component
public class WriteXlsxTool implements AgentTool {

    private static final String MIME_XLSX = GenerateUsageXlsxTool.MIME_XLSX;

    private final SimpleXlsxWriter xlsx;
    private final ObjectMapper mapper;

    public WriteXlsxTool(SimpleXlsxWriter xlsx, ObjectMapper mapper) {
        this.xlsx = xlsx;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "write_xlsx";
    }

    @Override
    public String description() {
        return "表データ（columns / rows）から Excel（xlsx）を生成する";
    }

    @Override
    public String argsHint() {
        return "{\"title\":\"string\",\"columns\":[\"string\"],\"rows\":[[\"string\"]]}";
    }

    @Override
    public List<String> requiredArgs() {
        return List.of("title", "columns", "rows");
    }

    @Override
    public ToolResult run(AgentContext ctx, String argsJson) {
        JsonNode args = AgentArgs.parse(argsJson);
        String title = AgentArgs.text(args, "title", "データ");

        List<String> columns = new ArrayList<>();
        args.path("columns").forEach(n -> columns.add(n.asText("")));

        List<List<String>> rows = new ArrayList<>();
        args.path("rows").forEach(rowNode -> {
            List<String> row = new ArrayList<>();
            rowNode.forEach(cell -> row.add(cell.asText("")));
            rows.add(row);
        });

        byte[] bytes = xlsx.write(title, columns, rows);

        ObjectNode out = mapper.createObjectNode();
        out.put("rows", rows.size());
        out.put("columns", columns.size());
        out.put("bytes", bytes.length);
        return ToolResult.artifact(out.toString(), bytes, "table.xlsx", MIME_XLSX);
    }
}
