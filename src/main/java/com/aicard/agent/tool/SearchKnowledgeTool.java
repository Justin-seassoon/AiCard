package com.aicard.agent.tool;

import com.aicard.agent.AgentArgs;
import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTool;
import com.aicard.agent.ToolResult;
import com.aicard.skb.model.SkbResult;
import com.aicard.skb.service.SkbService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/** 工具：检索员工知识库（受控 RAG），返回带出处的回答或 no_match。 */
@Component
public class SearchKnowledgeTool implements AgentTool {

    private final SkbService skb;
    private final ObjectMapper mapper;

    public SearchKnowledgeTool(SkbService skb, ObjectMapper mapper) {
        this.skb = skb;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "社内ナレッジベース（SKB）を検索し、根拠つきの回答を返す（no_match あり）";
    }

    @Override
    public String argsHint() {
        return "{\"question\":\"string\"}";
    }

    @Override
    public List<String> requiredArgs() {
        return List.of("question");
    }

    @Override
    public ToolResult run(AgentContext ctx, String argsJson) {
        String question = AgentArgs.text(AgentArgs.parse(argsJson), "question", "");
        SkbResult result = skb.answer(question, ctx.customerId(), ctx.storeId(), "skb");

        ArrayNode refs = mapper.createArrayNode();
        if (result.sourceRefs() != null) {
            result.sourceRefs().forEach(refs::add);
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("status", result.status());
        out.put("answer", result.answer());
        out.set("sourceRefs", refs);
        return ToolResult.of(out.toString());
    }
}
