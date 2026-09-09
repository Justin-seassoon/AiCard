package com.aicard.skb.seed;

import java.util.Arrays;
import java.util.List;

/**
 * 知识库检索关键词提取（轻量启发式）：去标点 → 删除日语停用词（助词/疑问词/敬语）→ 按空白切分 → 去重。
 * 用于关键词兜底检索（向量 miss 时）。汉字/片假名实词（朝食・免税・チェックアウト）不受假名停用词影响，
 * 能稳定提取；残留的噪声片段只影响精确度不影响召回，作为兜底足够。
 */
public class KeywordExtractor {

    private static final List<String> LONG_STOP_WORDS = List.of(
            "ですか", "ますか", "でしたか", "ましたか", "でした", "ました", "ください", "くださいませ",
            "お願い", "します", "いたします", "ございます", "ありますか", "いますか", "できますか",
            "教えて", "知りたい", "何時", "何歳", "何名", "何分", "どちら", "いくら", "どこ", "いつ",
            "どう", "なぜ", "だれ", "どの", "どれ", "なん", "から", "まで", "より", "なら", "けど", "何");

    private static final List<String> SHORT_STOP_WORDS = List.of(
            "は", "が", "を", "に", "へ", "と", "で", "の", "も", "や", "か", "ね", "よ", "わ", "し", "て", "ば", "お", "ご");

    public List<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String t = text.replaceAll("[、。！？!?，,・「」『』（）()\\[\\]\\s]+", " ");
        for (String w : LONG_STOP_WORDS) {
            t = t.replace(w, " ");
        }
        for (String w : SHORT_STOP_WORDS) {
            t = t.replace(w, " ");
        }
        return Arrays.stream(t.split("\\s+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }
}
