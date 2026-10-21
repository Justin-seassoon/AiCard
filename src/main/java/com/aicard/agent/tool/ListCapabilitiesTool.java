package com.aicard.agent.tool;

import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTool;
import com.aicard.agent.ToolRegistry;
import com.aicard.agent.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 工具：返回本 agent 能执行的操作清单（回答「何ができる？」这类问题）。
 * 用 {@link ObjectProvider} 延迟取 {@link ToolRegistry}，避免与注册表形成构造期循环依赖。
 */
@Component
public class ListCapabilitiesTool implements AgentTool {

    private final ObjectProvider<ToolRegistry> registryProvider;
    private final ObjectMapper mapper;

    public ListCapabilitiesTool(ObjectProvider<ToolRegistry> registryProvider, ObjectMapper mapper) {
        this.registryProvider = registryProvider;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "list_capabilities";
    }

    @Override
    public String description() {
        return "このエージェントが実行できる操作の一覧を返す（「何ができる？」への回答用）";
    }

    @Override
    public ToolResult run(AgentContext ctx, String argsJson) {
        ArrayNode items = mapper.createArrayNode();
        registryProvider.getObject().specs().forEach(items::add);
        ObjectNode out = mapper.createObjectNode();
        out.put("count", items.size());
        out.set("capabilities", items);
        return ToolResult.of(out.toString());
    }
}
