package com.aicard.agent.planner;

import com.aicard.agent.AgentContext;
import com.aicard.provider.api.ProviderException;
import com.aicard.skb.provider.LLMProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompositePlannerTest {

    private final AgentContext ctx = new AgentContext(1L, 1L);

    @Test
    void matchesKnownIntent() {
        assertThat(CompositePlanner.matchIntent("先週の修繕と点検をまとめて報告書にして")).isEqualTo("alert_report");
        assertThat(CompositePlanner.matchIntent("今週の利用状況をレポートにして")).isEqualTo("usage_report");
        assertThat(CompositePlanner.matchIntent("経費精算の締め日を調べて")).isNull();
    }

    @Test
    void fallsBackToRuleWhenLlmFailsAndIntentMatches() {
        LlmPlanner llm = mock(LlmPlanner.class);
        when(llm.next(any(), anyList(), anyList(), any())).thenThrow(new ProviderException("bad output"));
        CompositePlanner planner = new CompositePlanner(llm, new RulePlanner(new ObjectMapper()));

        LLMProvider.PlanStep step = planner.next("先週の修繕と点検をまとめて報告書にして", List.of(), List.of(), ctx);
        assertThat(step.tool()).isEqualTo("list_maintenance_orders");
    }

    @Test
    void propagatesLlmFailureWhenIntentUnknown() {
        LlmPlanner llm = mock(LlmPlanner.class);
        when(llm.next(any(), anyList(), anyList(), any())).thenThrow(new ProviderException("bad output"));
        CompositePlanner planner = new CompositePlanner(llm, new RulePlanner(new ObjectMapper()));

        assertThatThrownBy(() -> planner.next("何ができる？", List.of(), List.of(), ctx))
                .isInstanceOf(ProviderException.class);
    }
}
