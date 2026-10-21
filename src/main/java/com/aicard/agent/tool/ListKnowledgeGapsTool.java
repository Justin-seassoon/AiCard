package com.aicard.agent.tool;

import com.aicard.agent.AgentArgs;
import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTool;
import com.aicard.agent.ToolResult;
import com.aicard.skb.curation.GapStore;
import com.aicard.skb.curation.KnowledgeGap;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/** 工具：列出知识缺口（未回答的问题）。 */
@Component
public class ListKnowledgeGapsTool implements AgentTool {

    private final GapStore gaps;
    private final ObjectMapper mapper;

    public ListKnowledgeGapsTool(GapStore gaps, ObjectMapper mapper) {
        this.gaps = gaps;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "list_knowledge_gaps";
    }

    @Override
    public String description() {
        return "未回答（知識ギャップ）の質問一覧を返す";
    }

    @Override
    public String argsHint() {
        return "{\"status\":\"open|drafted|approved|rejected (optional)\"}";
    }

    @Override
    public ToolResult run(AgentContext ctx, String argsJson) {
        String status = AgentArgs.text(AgentArgs.parse(argsJson), "status", null);
        List<KnowledgeGap> list = gaps.list(ctx.customerId(), ctx.storeId(), "skb", status);

        ArrayNode items = mapper.createArrayNode();
        for (KnowledgeGap g : list) {
            items.add(mapper.createObjectNode()
                    .put("question", g.question())
                    .put("occurrences", g.occurrences())
                    .put("status", g.status()));
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("count", list.size());
        out.set("items", items);
        return ToolResult.of(out.toString());
    }
}
