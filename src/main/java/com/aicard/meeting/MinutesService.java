package com.aicard.meeting;

import com.aicard.docgen.DocxRenderer;
import com.aicard.skb.provider.LLMProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会议纪要生成：<b>内容由 LLM 起草，结构由本类归一化，排版由模板确定性渲染</b>。
 * <p>LLM 输出不可信：字段缺失/类型错误一律兜底为「要確認」，绝不补造事实；
 * 负责人/期限未明确出现时同样标「要確認」。</p>
 */
@Service
public class MinutesService {

    private static final String CONFIRM = "要確認";
    private static final String NOTE = "※ 本ファイルは AI が作成した下書きです。内容を確認してください。録音は30日後に削除されます。";
    private static final int MAX_TRANSCRIPT_CHARS = 80_000;
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter JST_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final LLMProvider llm;
    private final DocxRenderer docx;
    private final ObjectMapper mapper;

    public MinutesService(LLMProvider llm, DocxRenderer docx, ObjectMapper mapper) {
        this.llm = llm;
        this.docx = docx;
        this.mapper = mapper;
    }

    public record ActionDraft(String task, String owner, String dueDate, String sourceRef) {}

    public record Result(String json, List<ActionDraft> actions) {}

    public Result generate(Meeting meeting, MeetingTranscript transcript) {
        Map<String, Object> minutes = tryLlm(meeting, transcript);
        if (minutes == null) {
            minutes = fallback(meeting, transcript);
        }
        List<ActionDraft> actions = actions(minutes.get("actions"));
        try {
            return new Result(mapper.writeValueAsString(minutes), actions);
        } catch (Exception e) {
            throw new IllegalStateException("minutes json serialize failed", e);
        }
    }

    /** 生成 docx（議事録）。 */
    public byte[] renderDocx(Meeting meeting, Map<String, Object> minutes, List<MeetingActionItem> actions) {
        // 用户显式设置的会议标题优先；否则用 LLM 起草的标题
        String title = (meeting.getTitle() != null && !meeting.getTitle().isBlank())
                ? meeting.getTitle()
                : str(minutes.get("title"), "会議議事録");
        List<String> lines = new ArrayList<>();
        lines.add("日時：" + str(minutes.get("datetime"), CONFIRM));
        lines.add("場所：" + str(minutes.get("location"), CONFIRM));
        lines.add("出席者：" + String.join("、", list(minutes.get("attendees"))));
        lines.add("");

        lines.add("【議題】");
        for (String t : list(minutes.get("topics"))) {
            lines.add("・" + t);
        }
        lines.add("");

        lines.add("【討議内容】");
        for (String d : list(minutes.get("discussion"))) {
            lines.add("・" + d);
        }
        lines.add("");

        lines.add("【決定事項】");
        for (Map<String, Object> d : decisionMaps(minutes.get("decisions"))) {
            String text = str(d.get("text"), CONFIRM);
            String ref = str(d.get("ref"), "");
            lines.add("・" + text + (ref.isBlank() ? "" : "（根拠 " + ref + "）"));
        }
        lines.add("");

        lines.add("【保留事項】");
        for (String p : list(minutes.get("pending"))) {
            lines.add("・" + p);
        }
        lines.add("");

        lines.add("【アクション】");
        for (MeetingActionItem a : actions) {
            lines.add("・" + a.getTask() + "（担当：" + orConfirm(a.getOwner())
                    + " / 期限：" + orConfirm(a.getDueDate()) + "）");
        }
        lines.add("");

        lines.add("次回：" + str(minutes.get("next_meeting"), CONFIRM));
        lines.add("");
        lines.add("以上");
        return docx.render(title, lines, NOTE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryLlm(Meeting meeting, MeetingTranscript transcript) {
        String text = transcript.text();
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.length() > MAX_TRANSCRIPT_CHARS) {
            text = text.substring(0, MAX_TRANSCRIPT_CHARS);
        }
        try {
            String raw = llm.draftMinutes(text);
            String json = extractJson(raw);
            if (json == null) {
                return null;
            }
            JsonNode root = mapper.readTree(json);
            if (!root.isObject()) {
                return null;
            }
            Map<String, Object> src = mapper.convertValue(root, new TypeReference<Map<String, Object>>() {});
            return normalize(meeting, transcript, src);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把 LLM 输出归一化成固定结构；缺失字段 → 要確認。 */
    private Map<String, Object> normalize(Meeting meeting, MeetingTranscript transcript, Map<String, Object> src) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", str(src.get("title"), meeting.getTitle() == null ? "会議議事録" : meeting.getTitle()));
        m.put("datetime", str(src.get("datetime"), defaultDatetime(meeting)));
        m.put("location", str(src.get("location"), CONFIRM));
        List<String> attendees = nonEmptyList(src.get("attendees"), speakers(transcript));
        m.put("attendees", attendees.isEmpty() ? List.of(CONFIRM) : attendees);
        m.put("topics", nonEmptyList(src.get("topics"), List.of(CONFIRM)));
        m.put("discussion", nonEmptyList(src.get("discussion"), List.of(CONFIRM)));
        m.put("decisions", decisions(src.get("decisions")));
        m.put("pending", list(src.get("pending")));
        m.put("next_meeting", str(src.get("next_meeting"), CONFIRM));
        m.put("actions", normalizedActions(src.get("actions")));
        return m;
    }

    /** LLM 不可用时的确定性兜底：只陈述转写中出现的内容，不补造。 */
    private Map<String, Object> fallback(Meeting meeting, MeetingTranscript transcript) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", meeting.getTitle() == null ? "会議議事録" : meeting.getTitle());
        m.put("datetime", defaultDatetime(meeting));
        m.put("location", CONFIRM);
        m.put("attendees", speakers(transcript));
        m.put("topics", List.of(CONFIRM));
        m.put("discussion", transcript.segments().stream()
                .map(s -> "[" + MeetingTranscriptionService.ref(s.startSec()) + "] " + s.text())
                .toList());
        m.put("decisions", List.of());
        m.put("pending", List.of(CONFIRM));
        m.put("next_meeting", CONFIRM);
        m.put("actions", List.of());
        return m;
    }

    private List<Object> decisions(Object raw) {
        List<Object> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> map) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("text", str(map.get("text"), CONFIRM));
                d.put("ref", str(map.get("ref"), ""));
                out.add(d);
            } else if (o != null && !String.valueOf(o).isBlank()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("text", String.valueOf(o));
                d.put("ref", "");
                out.add(d);
            }
        }
        return out;
    }

    private List<Object> normalizedActions(Object raw) {
        List<Object> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map)) {
                continue;
            }
            String task = str(map.get("task"), "").trim();
            if (task.isBlank()) {
                continue;
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("task", task);
            a.put("owner", orConfirm(str(map.get("owner"), null)));
            a.put("due", orConfirm(str(map.get("due"), null)));
            a.put("ref", str(map.get("ref"), ""));
            out.add(a);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<ActionDraft> actions(Object raw) {
        List<ActionDraft> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> map) {
                out.add(new ActionDraft(
                        str(map.get("task"), ""),
                        str(map.get("owner"), CONFIRM),
                        str(map.get("due"), CONFIRM),
                        str(map.get("ref"), "")));
            }
        }
        return out;
    }

    private List<String> speakers(MeetingTranscript transcript) {
        Set<String> names = new LinkedHashSet<>();
        for (MeetingTranscript.Segment s : transcript.segments()) {
            if (s.speaker() != null && !s.speaker().isBlank()) {
                names.add(s.speaker());
            }
        }
        return new ArrayList<>(names);
    }

    private static List<String> nonEmptyList(Object raw, List<String> def) {
        List<String> list = list(raw);
        return list.isEmpty() ? def : list;
    }

    private static List<String> list(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> decisionMaps(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
        }
        return out;
    }

    private static String defaultDatetime(Meeting meeting) {
        if (meeting.getStartedAt() == null) {
            return CONFIRM;
        }
        return JST_FMT.format(meeting.getStartedAt().atZone(JST));
    }

    private static String str(Object v, String def) {
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static String orConfirm(String s) {
        return s == null || s.isBlank() || CONFIRM.equals(s) ? CONFIRM : s;
    }

    /** 从模型输出提取 JSON（容忍 ```json 代码块与前后说明）。 */
    private static String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String t = content.trim();
        if (t.startsWith("```")) {
            int start = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (start >= 0 && end > start) {
                t = t.substring(start + 1, end).trim();
            }
        }
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        return (a >= 0 && b > a) ? t.substring(a, b + 1) : null;
    }
}
