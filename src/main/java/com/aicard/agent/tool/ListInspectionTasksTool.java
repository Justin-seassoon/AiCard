package com.aicard.agent.tool;

import com.aicard.agent.AgentArgs;
import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTool;
import com.aicard.agent.ToolResult;
import com.aicard.maintenance.domain.InspectionTask;
import com.aicard.maintenance.service.InspectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/** 工具：列出本店点检任务（输出 count + items）。 */
@Component
public class ListInspectionTasksTool implements AgentTool {

    private final InspectionService inspections;
    private final ObjectMapper mapper;

    public ListInspectionTasksTool(InspectionService inspections, ObjectMapper mapper) {
        this.inspections = inspections;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "list_inspection_tasks";
    }

    @Override
    public String description() {
        return "列出本店の点検タスク（任意で status で絞り込み）";
    }

    @Override
    public ToolResult run(AgentContext ctx, String argsJson) {
        String status = AgentArgs.text(AgentArgs.parse(argsJson), "status", null);
        List<InspectionTask> list = inspections.list(ctx.customerId(), ctx.storeId(), status);
        ArrayNode items = mapper.createArrayNode();
        for (InspectionTask t : list) {
            items.add(mapper.createObjectNode()
                    .put("taskNo", t.getTaskNo()).put("title", t.getTitle()).put("status", t.getStatus()));
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("count", list.size());
        root.set("items", items);
        return ToolResult.of(root.toString());
    }
}
