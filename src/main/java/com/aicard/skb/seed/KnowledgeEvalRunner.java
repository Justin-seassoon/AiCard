package com.aicard.skb.seed;

import com.aicard.skb.model.SkbResult;
import com.aicard.skb.service.SkbService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索质量评估工具：内置一组日语测试问题（覆盖便利店/酒店高频问答），
 * 逐个跑真实「检索 → LLM 生成 → 校验」，输出答案与引用，并汇总命中率。
 *
 * <p>触发方式（连已灌库的 db_ai_card）：
 * <pre>
 * java -jar app.jar --skb.eval.enabled=true --skb.eval.customer-id=1 --skb.eval.store-id=1
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "skb.eval.enabled", havingValue = "true")
public class KnowledgeEvalRunner implements CommandLineRunner {

    private final SkbService service;

    @Value("${skb.eval.customer-id:1}")
    private Long customerId;

    @Value("${skb.eval.store-id:1}")
    private Long storeId;

    /** 测试问题集：均取自已灌库的日语知识库，用于验证「应命中」的检索质量。 */
    private static final List<String> QUESTIONS = List.of(
            // 便利店（10）
            "営業時間を教えてください。",
            "免税の対象になるのは誰ですか？",
            "レジ袋は有料ですか？",
            "消費税の税率を教えてください。",
            "お酒は何歳から買えますか？",
            "ATM はありますか？",
            "消費期限と賞味期限の違いは？",
            "偽札らしきものを受け取ったら？",
            "万引きを見つけたらどうしますか？",
            "外国のお客様にはどう対応しますか？",
            // 酒店（10）
            "チェックアウトは何時ですか？",
            "朝食は何時からですか？",
            "Wi-Fi のパスワードは何ですか？",
            "大浴場は何時から入れますか？",
            "キャンセル料はかかりますか？",
            "忘れ物をしたらどうすれば？",
            "火災が起きたらどうしますか？",
            "宿泊税とは何ですか？",
            "駐車場はありますか？",
            "浴衣の正しい着方は？"
    );

    public KnowledgeEvalRunner(SkbService service) {
        this.service = service;
    }

    @Override
    public void run(String... args) {
        int ok = 0;
        int noMatch = 0;
        System.out.println("=== 知识库检索质量评估开始（共 " + QUESTIONS.size() + " 题）===\n");
        for (int i = 0; i < QUESTIONS.size(); i++) {
            String q = QUESTIONS.get(i);
            SkbResult r = service.answer(q, customerId, storeId);
            boolean isOk = "ok".equals(r.status());
            if (isOk) {
                ok++;
            } else {
                noMatch++;
            }
            System.out.println("[" + (i + 1) + "] " + q);
            System.out.println("    状态=" + r.status() + " | 答案=" + r.answer());
            if (!r.sourceRefs().isEmpty()) {
                System.out.println("    引用=" + r.sourceRefs());
            }
            System.out.println();
        }
        System.out.println("=== 汇总 ===");
        System.out.println("命中(ok)=" + ok + " / 未命中(no_match)=" + noMatch + " / 共=" + QUESTIONS.size());
        System.out.println("命中率=" + (ok * 100 / QUESTIONS.size()) + "%");
    }
}
