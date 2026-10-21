package com.aicard.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具注册表：收集全部 {@link AgentTool} bean，按名查找。 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> byName = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> tools) {
        for (AgentTool t : tools) {
            byName.put(t.name(), t);
        }
    }

    public AgentTool get(String name) {
        return byName.get(name);
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    /** 供 LLM 规划的工具清单（名 + 描述 + 参数提示）。 */
    public List<String> specs() {
        return byName.values().stream()
                .map(t -> t.name() + "：" + t.description() + "（args: " + t.argsHint() + "）")
                .toList();
    }
}
