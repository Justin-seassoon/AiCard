package com.aicard.skb.provider.aibridgex;

import com.aicard.skb.model.LlmResult;
import com.aicard.skb.model.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** aibridgex LLM 真实冒烟测试：仅当设置 AIBRIDGEX_KEY 环境变量时运行。 */
@EnabledIfEnvironmentVariable(named = "AIBRIDGEX_KEY", matches = ".+")
class AibridgexLlmProviderSmokeTest {

    @Test
    void generatesAnswerWithCitations() {
        AibridgexLlmProvider p = new AibridgexLlmProvider(
                System.getenv().getOrDefault("AIBRIDGEX_BASE_URL", "https://api.aibridgex.net/v1"),
                System.getenv("AIBRIDGEX_KEY"),
                System.getenv().getOrDefault("AIBRIDGEX_MODEL", "deepseek-v4-flash"));

        List<RetrievedChunk> context = List.of(
                new RetrievedChunk(42L,
                        "Q: 朝食は何時からですか？ A: 朝食は 6 時半から 9 時半まで、1 階レストランでご用意しております。",
                        "餐饮服务", "v1", 0.9));

        LlmResult r = p.generate("朝食は何時からですか？", context);

        assertThat(r.answer()).isNotBlank();
        System.out.println("answer=" + r.answer());
        System.out.println("cited=" + r.citedChunkIds());
    }
}
