package com.aicard.skb.provider;

import com.aicard.skb.model.LlmResult;
import com.aicard.skb.model.RetrievedChunk;

import java.util.List;

/** 受限生成（LLM）：基于命中片段回答问题，引用必须选自 context 片段 ID。demo 用 Mock。 */
public interface LLMProvider {
    LlmResult generate(String question, List<RetrievedChunk> context);

    /** 文本翻译：把 text 翻译成 targetLang（VKB 答案反向翻译用）。 */
    String translate(String text, String targetLang);

    /**
     * 起草候选（ドラフト・要確認）：为「未命中的问题」基于相近片段拟一条候选答案。
     * 与 {@link #generate} 不同——**允许超出既有知识**（仅供参考），必须经**人工审核**后才入库。
     *
     * @param related 相近但未必达检索阈值的片段，可为空（此时应输出「候補・要確認」模板）
     */
    String draftAnswer(String question, List<RetrievedChunk> related);

    /**
     * 起草社内文书（ドラフト・要確認）：按指令生成一段日语商务文书正文。
     * 与 {@link #generate}/{@link #draftAnswer} 语义独立——这是**文书的正文草稿**，须人工确认。
     */
    String draftDocument(String instruction);

    /**
     * 自主规划一步（② agent 执行）：给定目标、可用工具与执行历史，返回下一步工具调用，或标记完成。
     *
     * @param toolSpecs 可用工具清单（名 + 描述）
     * @param history   已执行步骤摘要
     */
    PlanStep planStep(String goal, List<String> toolSpecs, List<String> history);

    /** 规划结果：{@code done=true} 表示任务完成；否则调用 {@code tool}，参数为 JSON {@code argsJson}。 */
    record PlanStep(String tool, String argsJson, boolean done) {
        public static PlanStep tool(String tool, String argsJson) {
            return new PlanStep(tool, argsJson, false);
        }

        public static PlanStep finished() {
            return new PlanStep(null, null, true);
        }
    }
}
