package com.aicard.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 工具参数（JSON 字符串）的轻量解析。 */
public final class AgentArgs {

    private static final ObjectMapper M = new ObjectMapper();

    private AgentArgs() {}

    public static JsonNode parse(String argsJson) {
        try {
            return (argsJson == null || argsJson.isBlank()) ? M.createObjectNode() : M.readTree(argsJson);
        } catch (Exception e) {
            return M.createObjectNode();
        }
    }

    public static String text(JsonNode node, String field, String def) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : def;
    }

    public static int intVal(JsonNode node, String field, int def) {
        return node != null && node.hasNonNull(field) ? node.get(field).asInt() : def;
    }

    /** 返回缺失的必填参数名（用于执行前校验）。 */
    public static java.util.List<String> missing(JsonNode node, java.util.Collection<String> required) {
        java.util.List<String> miss = new java.util.ArrayList<>();
        for (String f : required) {
            if (node == null || !node.hasNonNull(f)) {
                miss.add(f);
            }
        }
        return miss;
    }
}
