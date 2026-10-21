package com.aicard.agent.tool;

import com.aicard.agent.AgentArgs;
import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTool;
import com.aicard.agent.ToolResult;
import com.aicard.maintenance.domain.MaintenanceOrder;
import com.aicard.maintenance.service.MaintenanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/** 工具：列出本店维修单（输出 count + items）。 */
@Component
public class ListMaintenanceOrdersTool implements AgentTool {

    private final MaintenanceService orders;
    private final ObjectMapper mapper;

    public ListMaintenanceOrdersTool(MaintenanceService orders, ObjectMapper mapper) {
        this.orders = orders;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "list_maintenance_orders";
    }

    @Override
    public String description() {
        return "列出本店の修繕オーダー（任意で status で絞り込み）";
    }

    @Override
    public String argsHint() {
        return "{\"status\":\"string (optional: open/processing/done)\"}";
    }

    @Override
    public ToolResult run(AgentContext ctx, String argsJson) {
        String status = AgentArgs.text(AgentArgs.parse(argsJson), "status", null);
        List<MaintenanceOrder> list = orders.list(ctx.customerId(), ctx.storeId(), status);
        ArrayNode items = mapper.createArrayNode();
        for (MaintenanceOrder o : list) {
            items.add(mapper.createObjectNode()
                    .put("orderNo", o.getOrderNo()).put("title", o.getTitle()).put("status", o.getStatus()));
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("count", list.size());
        root.set("items", items);
        return ToolResult.of(root.toString());
    }
}
