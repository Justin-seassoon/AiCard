package com.aicard.agent;

import com.aicard.skb.provider.LLMProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 最终答复（②-A）：把执行步骤的观察结果变成**用户可读的答复**。
 * <ul>
 *   <li>有产物 → 确定性文案（文件名 + 步数），不调 LLM；</li>
 *   <li>无产物 → 由 LLM 根据步骤输出概括（1~3 句日语）；LLM 失败则回退为「完了：N 步骤」。</li>
 * </ul>
 */
@Service
public class ResultComposer {

    private static final int OUTPUT_SNIPPET = 300;

    private final LLMProvider llm;

    public ResultComposer(LLMProvider llm) {
        this.llm = llm;
    }

    public String compose(String goal, List<StepRecord> history, String artifactName) {
        if (artifactName != null && !artifactName.isBlank()) {
            return "完了：" + artifactName + " を生成しました（" + history.size() + " ステップ）";
        }
        if (history.isEmpty()) {
            return "完了：0 ステップ";
        }

        String instruction = "目標：" + goal + "\n実行結果：\n"
                + history.stream()
                        .map(h -> "- " + h.tool() + " → " + snippet(h.output()))
                        .collect(Collectors.joining("\n"))
                + "\n\n上記の実行結果だけを根拠に、日本語で簡潔に回答してください。";

        try {
            String answer = llm.summarize(instruction);
            if (answer != null && !answer.isBlank()) {
                return answer.trim();
            }
        } catch (RuntimeException ignore) {
            // LLM 汇总失败不影响任务完成 → 回退确定性文案
        }
        return fallback(history);
    }

    private static String fallback(List<StepRecord> history) {
        return "完了：" + history.size() + " ステップ";
    }

    private static String snippet(String output) {
        if (output == null) {
            return "";
        }
        return output.length() <= OUTPUT_SNIPPET ? output : output.substring(0, OUTPUT_SNIPPET) + "…";
    }
}
