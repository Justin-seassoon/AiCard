package com.aicard.skb.seed;

import java.util.ArrayList;
import java.util.List;

/**
 * 演示知识库 markdown 解析器：把日语版「一问一答」markdown 解析成主题 + 问答对结构。
 *
 * <p>支持格式（见 {@code docs/演示知识库-*-日语版.md}）：
 * <pre>
 * ## 0. 店铺基本信息（店舗情報）
 * **Q：営業時間を教えてください。**
 * A：当店は 24 時間営業で、年中無休でございます。
 * </pre>
 */
public final class MarkdownQaParser {

    /** 一个主题（document）及其下的问答对（chunk）。 */
    public record Topic(String title, List<Qa> pairs) {}

    /** 一问一答。 */
    public record Qa(String question, String answer) {}

    private MarkdownQaParser() {}

    public static List<Topic> parse(List<String> lines) {
        List<Topic> topics = new ArrayList<>();
        String currentTopic = null;
        List<Qa> pairs = new ArrayList<>();
        String pendingQuestion = null;

        for (String raw : lines) {
            String t = raw.trim();
            if (t.startsWith("## ")) {
                flush(topics, currentTopic, pairs);
                currentTopic = t.substring(3).trim();
                pairs = new ArrayList<>();
            } else if (t.startsWith("**Q：") || t.startsWith("**Q:")) {
                pendingQuestion = extractQuestion(t);
            } else if (t.startsWith("A：") || t.startsWith("A:")) {
                String answer = stripPrefix(t);
                if (pendingQuestion != null) {
                    pairs.add(new Qa(pendingQuestion, answer));
                    pendingQuestion = null;
                }
            }
        }
        flush(topics, currentTopic, pairs);
        return topics;
    }

    private static void flush(List<Topic> topics, String title, List<Qa> pairs) {
        if (title != null) {
            topics.add(new Topic(title, pairs));
        }
    }

    private static String extractQuestion(String line) {
        String q = line;
        if (q.startsWith("**")) {
            q = q.substring(2);
        }
        q = stripPrefix(q);
        if (q.endsWith("**")) {
            q = q.substring(0, q.length() - 2);
        }
        return q.trim();
    }

    /** 去掉「Q：」「A：」等前缀。 */
    private static String stripPrefix(String s) {
        if (s.length() > 1 && s.charAt(1) == '：') {
            return s.substring(2).trim();
        }
        if (s.length() > 1 && s.charAt(1) == ':') {
            return s.substring(2).trim();
        }
        return s.trim();
    }
}
