package com.aicard.skb.provider.mock;

import com.aicard.skb.model.LlmResult;
import com.aicard.skb.model.RetrievedChunk;
import com.aicard.skb.provider.LLMProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 可编程 Mock LLM：返回预设答案 + 引用 / 预设翻译，测试/开发用。 */
public class MockLlmProvider implements LLMProvider {

    private final AtomicReference<LlmResult> result =
            new AtomicReference<>(new LlmResult("暂无明确说明", List.of()));
    private final AtomicReference<String> translatedText = new AtomicReference<>("こんにちは");
    private final AtomicReference<String> draft = new AtomicReference<>("（候補・要確認）");
    private final AtomicReference<String> document = new AtomicReference<>("（下書き・要確認）");
    private final AtomicReference<String> summary = new AtomicReference<>("（要約）");
    private final AtomicReference<String> minutes = new AtomicReference<>(DEFAULT_MINUTES);
    private final AtomicReference<PlanStep> plan = new AtomicReference<>(PlanStep.finished());
    private final java.util.concurrent.ConcurrentLinkedQueue<PlanStep> planQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public void setResult(LlmResult r) { result.set(r); }
    public void setTranslatedText(String t) { translatedText.set(t); }
    public void setDraft(String d) { draft.set(d); }
    public void setDocument(String d) { document.set(d); }
    public void setSummary(String s) { summary.set(s); }
    public void setMinutes(String m) { minutes.set(m); }
    public void setPlan(PlanStep p) { plan.set(p); }

    /** 设置多步规划序列（依次返回，耗尽后回退到 {@link #setPlan} 的值）。 */
    public void setPlans(java.util.List<PlanStep> steps) {
        planQueue.clear();
        planQueue.addAll(steps);
    }

    @Override
    public LlmResult generate(String question, List<RetrievedChunk> context) {
        return result.get();
    }

    @Override
    public String translate(String text, String targetLang) {
        return translatedText.get();
    }

    @Override
    public String draftAnswer(String question, List<RetrievedChunk> related) {
        return draft.get();
    }

    @Override
    public String draftDocument(String instruction) {
        return document.get();
    }

    @Override
    public String summarize(String instruction) {
        return summary.get();
    }

    @Override
    public String draftMinutes(String transcript) {
        return minutes.get();
    }

    @Override
    public PlanStep planStep(String goal, List<String> toolSpecs, List<String> history) {
        PlanStep next = planQueue.poll();
        return next != null ? next : plan.get();
    }

    /** 演示用会议纪要（结构化 JSON；含「要確認」与时间戳根拠）。 */
    private static final String DEFAULT_MINUTES = """
            {
              "title": "定例会議",
              "datetime": "2026-10-23 10:00",
              "location": "本社会議室",
              "attendees": ["田中（司会）", "佐藤", "鈴木（総務）"],
              "topics": ["経費精算の締め日変更", "来月の棚卸しの進め方"],
              "discussion": [
                "経費精算は現在25日締めだが、交通費の入力遅れが多い。",
                "月末締めに変更すると、経理処理は翌月3日までかかる見込み。",
                "棚卸しは11月10日の週に実施し、各店舗の担当者は店長に確認する。"
              ],
              "decisions": [
                {"text": "来月から経費精算を月末締めに変更する", "ref": "01:20"},
                {"text": "棚卸しを11月10日の週に実施する", "ref": "01:42"}
              ],
              "pending": ["各店舗の棚卸し担当者の最終確認"],
              "next_meeting": "11月7日10時 本会議室",
              "actions": [
                {"task": "経費精算の締め日変更に関する社内通知の作成", "owner": "鈴木", "due": "10月30日", "ref": "01:20"},
                {"task": "棚卸し実施計画の作成", "owner": "佐藤", "due": "11月5日", "ref": "02:00"},
                {"task": "備品在庫確認", "owner": "鈴木", "due": "要確認", "ref": "02:12"}
              ]
            }
            """;
}
