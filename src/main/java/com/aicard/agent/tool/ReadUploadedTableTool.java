package com.aicard.agent.tool;

import com.aicard.agent.AgentArgs;
import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTool;
import com.aicard.agent.ToolResult;
import com.aicard.docgen.UploadStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/** 工具：读取已上传并解析的表格（供「把这份 Excel 汇总成报告」这类任务）。 */
@Component
public class ReadUploadedTableTool implements AgentTool {

    private static final int MAX_ROWS_PER_SHEET = 200;

    private final UploadStore uploads;
    private final ObjectMapper mapper;

    public ReadUploadedTableTool(UploadStore uploads, ObjectMapper mapper) {
        this.uploads = uploads;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "read_uploaded_table";
    }

    @Override
    public String description() {
        return "アップロード済みの表（Excel/CSV）の解析結果を返す";
    }

    @Override
    public String argsHint() {
        return "{\"upload_id\":\"int\"}";
    }

    @Override
    public List<String> requiredArgs() {
        return List.of("upload_id");
    }

    @Override
    public ToolResult run(AgentContext ctx, String argsJson) {
        long id = AgentArgs.intVal(AgentArgs.parse(argsJson), "upload_id", -1);
        UploadStore.StoredUpload u = uploads.find(id, ctx.customerId(), ctx.storeId()).orElse(null);
        if (u == null || u.parsed() == null) {
            return ToolResult.of("{\"error\":\"upload not found\"}");
        }
        try {
            JsonNode parsed = mapper.readTree(u.parsed());
            ArrayNode sheets = mapper.createArrayNode();
            for (JsonNode sheet : parsed.path("sheets")) {
                ObjectNode s = mapper.createObjectNode();
                s.put("name", sheet.path("name").asText(""));
                s.set("headers", sheet.path("headers"));
                ArrayNode rows = mapper.createArrayNode();
                int n = 0;
                for (JsonNode row : sheet.path("rows")) {
                    if (n++ >= MAX_ROWS_PER_SHEET) {
                        break;
                    }
                    rows.add(row);
                }
                s.put("rowCount", sheet.path("rows").size());
                s.set("rows", rows);
                s.set("notes", sheet.path("notes"));
                s.set("summaries", sheet.path("summaries"));
                sheets.add(s);
            }
            ObjectNode out = mapper.createObjectNode();
            out.put("uploadId", u.id());
            out.put("filename", u.filename());
            out.set("sheets", sheets);
            return ToolResult.of(out.toString());
        } catch (Exception e) {
            return ToolResult.of("{\"error\":\"parse failed\"}");
        }
    }
}
