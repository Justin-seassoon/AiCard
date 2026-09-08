package com.aicard.skb.seed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownQaParserTest {

    @Test
    void parsesTopicsAndQaPairs() {
        List<String> lines = List.of(
                "## 0. 店铺基本信息（店舗情報）",
                "",
                "**Q：営業時間を教えてください。**",
                "A：当店は 24 時間営業で、年中無休でございます。",
                "",
                "**Q：免税はいつ手続きできますか？**",
                "A：免税カウンターは午前 9 時から午後 9 時まで承っております。",
                "",
                "## 1. 收银与支付（レジ・決済）",
                "",
                "**Q：レジ袋は有料ですか？**",
                "A：はい、レジ袋はサイズにより 1 枚 1 円・3 円・5 円いただいております。"
        );

        List<MarkdownQaParser.Topic> topics = MarkdownQaParser.parse(lines);

        assertThat(topics).hasSize(2);
        assertThat(topics.get(0).title()).isEqualTo("0. 店铺基本信息（店舗情報）");
        assertThat(topics.get(0).pairs()).hasSize(2);
        assertThat(topics.get(0).pairs().get(0).question()).isEqualTo("営業時間を教えてください。");
        assertThat(topics.get(0).pairs().get(0).answer()).contains("24 時間営業");
        assertThat(topics.get(1).pairs()).hasSize(1);
        assertThat(topics.get(1).pairs().get(0).answer()).contains("3 円・5 円");
    }

    @Test
    void ignoresBlankLinesAndHeadings() {
        List<String> lines = List.of(
                "# AI 工牌 1.0 · 演示知识库",
                "> 用途：演示",
                "---",
                "## 主题（テーマ）",
                "**Q：質問は？**",
                "A：回答です。"
        );

        List<MarkdownQaParser.Topic> topics = MarkdownQaParser.parse(lines);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).pairs()).hasSize(1);
    }
}
