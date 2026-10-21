package com.aicard.agent;

import com.aicard.provider.api.ProviderException;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.provider.mock.MockLlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResultComposerTest {

    private static StepRecord step(String tool, String output) {
        return new StepRecord(tool, "{}", StepRecord.OK, output, null, 1, 1);
    }

    @Test
    void artifactGivesDeterministicResult() {
        ResultComposer composer = new ResultComposer(new MockLlmProvider());
        String result = composer.compose("レポートを作って", List.of(step("write_xlsx", "{}")), "usage.xlsx");
        assertThat(result).contains("usage.xlsx").contains("1 ステップ");
    }

    @Test
    void answerGoalIsSummarizedByLlm() {
        MockLlmProvider llm = new MockLlmProvider();
        llm.setSummary("今週は 6 件です。");
        ResultComposer composer = new ResultComposer(llm);

        String result = composer.compose("今週の翻訳件数は？",
                List.of(step("usage_summary", "{\"turnCount\":6}")), null);
        assertThat(result).isEqualTo("今週は 6 件です。");
    }

    @Test
    void fallsBackWhenLlmFails() {
        LLMProvider llm = mock(LLMProvider.class);
        when(llm.summarize(anyString())).thenThrow(new ProviderException("boom"));
        ResultComposer composer = new ResultComposer(llm);

        String result = composer.compose("今週の翻訳件数は？",
                List.of(step("usage_summary", "{}")), null);
        assertThat(result).isEqualTo("完了：1 ステップ");
    }
}
