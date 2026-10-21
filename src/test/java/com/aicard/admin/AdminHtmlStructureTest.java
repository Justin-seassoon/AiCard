package com.aicard.admin;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台静态页结构守卫：标签闭合 + tab↔panel 对应。
 * 静态页无运行时测试，曾因缺少 {@code </section>} 导致面板被嵌进隐藏面板而不显示。
 */
class AdminHtmlStructureTest {

    @Test
    void panelsAreBalancedAndMatchTabs() throws Exception {
        String html;
        try (InputStream in = getClass().getResourceAsStream("/static/admin.html")) {
            assertThat(in).as("admin.html 在 classpath 上").isNotNull();
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(count(html, "<section"))
                .as("<section> 与 </section> 数量必须一致")
                .isEqualTo(count(html, "</section>"));

        Matcher tab = Pattern.compile("data-tab=\"([a-z]+)\"").matcher(html);
        int tabs = 0;
        while (tab.find()) {
            tabs++;
            assertThat(html)
                    .as("tab %s 必须有对应的 panel-%s", tab.group(1), tab.group(1))
                    .contains("id=\"panel-" + tab.group(1) + "\"");
        }
        assertThat(tabs).as("页签数量").isGreaterThanOrEqualTo(7);
    }

    private static int count(String s, String token) {
        int n = 0;
        for (int i = s.indexOf(token); i >= 0; i = s.indexOf(token, i + token.length())) {
            n++;
        }
        return n;
    }
}
