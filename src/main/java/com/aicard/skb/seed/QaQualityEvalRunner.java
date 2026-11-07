package com.aicard.skb.seed;

import com.aicard.skb.model.SkbResult;
import com.aicard.skb.service.SkbService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 问答质量评估（口径：命中 = 有答案且答案正确）：
 * <ul>
 *   <li>应答题（日语直答 + 中文跨语言）：返回 no_match 记为漏答；</li>
 *   <li>应拒题（确认知识库无覆盖的话题）：返回 ok 记为误答。</li>
 * </ul>
 *
 * <p>触发方式（连已灌库的库）：
 * <pre>
 * java -jar app.jar --skb.eval.quality=true --skb.eval.customer-id=1 --skb.eval.store-id=1
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "skb.eval.quality", havingValue = "true")
public class QaQualityEvalRunner implements CommandLineRunner {

    private final SkbService service;

    @Value("${skb.eval.customer-id:1}")
    private Long customerId;

    @Value("${skb.eval.store-id:1}")
    private Long storeId;

    /** 应答题（日语）：均取自已灌库的日语知识库。 */
    private static final List<String> SHOULD_ANSWER_JA = List.of(
            "営業時間を教えてください。",
            "チェックアウトは何時ですか？",
            "朝食は何時からですか？",
            "Wi-Fi のパスワードは何ですか？",
            "大浴場は何時から入れますか？",
            "駐車場はありますか？",
            "キャンセル料はかかりますか？",
            "忘れ物をしたらどうすれば？",
            "消費税の税率を教えてください。",
            "お酒は何歳から買えますか？"
    );

    /** 应答题（中文，走跨语言检索 + 本地化生成）。 */
    private static final List<String> SHOULD_ANSWER_ZH = List.of(
            "早餐几点开始？",
            "退房时间是几点？",
            "Wi-Fi密码是什么？",
            "可以带宠物入住吗？",
            "有停车场吗？",
            "可以帮忙寄存行李吗？"
    );

    /** 应拒题（中文）：均为确认知识库无覆盖的话题（健身房 / 桑拿 / 泳池 / 卡拉OK 等）。 */
    private static final List<String> SHOULD_REJECT_ZH = List.of(
            "酒店有健身房吗？",
            "酒店有桑拿房吗？",
            "酒店有游泳池吗？",
            "可以唱卡拉OK吗？",
            "可以租车吗？",
            "有宴会厅吗？",
            "有按摩服务吗？",
            "有瑜伽课程吗？",
            "有儿童游乐区吗？",
            "可以借自行车吗？",
            "可以租滑雪装备吗？",
            "可以借空气净化器吗？",
            "可以借婴儿车吗？",
            "有投币储物柜吗？"
    );

    public QaQualityEvalRunner(SkbService service) {
        this.service = service;
    }

    @Override
    public void run(String... args) {
        System.out.println("=== 问答质量评估（命中=答案正确）===\n");

        int answerOk = 0;
        int answerTotal = 0;
        for (String q : SHOULD_ANSWER_JA) {
            answerTotal++;
            try {
                SkbResult r = service.answer(q, customerId, storeId, "skb");
                boolean ok = "ok".equals(r.status());
                if (ok) {
                    answerOk++;
                }
                System.out.println("[JA " + (ok ? "命中" : "漏答") + "] " + q + " → " + r.answer());
            } catch (RuntimeException e) {
                System.out.println("[JA 调用失败] " + q + " → " + e.getMessage());
            }
        }
        for (String q : SHOULD_ANSWER_ZH) {
            answerTotal++;
            try {
                SkbService.LocalizedAnswer r = service.answerLocalized(q, customerId, storeId, "skb", "zh-CN");
                boolean ok = "ok".equals(r.result().status());
                if (ok) {
                    answerOk++;
                }
                String shown = r.localizedAnswer() != null ? r.localizedAnswer() : r.result().answer();
                System.out.println("[ZH " + (ok ? "命中" : "漏答") + "] " + q + " → " + shown);
            } catch (RuntimeException e) {
                System.out.println("[ZH 调用失败] " + q + " → " + e.getMessage());
            }
        }

        int wrongAccept = 0;
        for (String q : SHOULD_REJECT_ZH) {
            try {
                SkbService.LocalizedAnswer r = service.answerLocalized(q, customerId, storeId, "skb", "zh-CN");
                boolean answered = "ok".equals(r.result().status());
                if (answered) {
                    wrongAccept++;
                }
                String shown = r.localizedAnswer() != null ? r.localizedAnswer() : r.result().answer();
                System.out.println("[拒答 " + (answered ? "误答" : "正确拒答") + "] " + q + " → " + shown);
            } catch (RuntimeException e) {
                System.out.println("[拒答 调用失败] " + q + " → " + e.getMessage());
            }
        }

        System.out.println();
        System.out.printf("=== 汇总：应答题命中 %d/%d (%.1f%%) | 应拒题误答 %d/%d (%.1f%%) ===%n",
                answerOk, answerTotal, 100.0 * answerOk / answerTotal,
                wrongAccept, SHOULD_REJECT_ZH.size(), 100.0 * wrongAccept / SHOULD_REJECT_ZH.size());
    }
}
