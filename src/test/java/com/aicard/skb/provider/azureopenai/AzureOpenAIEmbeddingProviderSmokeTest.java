package com.aicard.skb.provider.azureopenai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Azure OpenAI embedding 真实冒烟测试：仅当设置 AZURE_OPENAI_KEY 环境变量时运行。
 * 验证 endpoint/key/deployment 可用，且返回 1536 维向量。
 */
@EnabledIfEnvironmentVariable(named = "AZURE_OPENAI_KEY", matches = ".+")
class AzureOpenAIEmbeddingProviderSmokeTest {

    @Test
    void embedsJapaneseTextTo1536Dims() {
        AzureOpenAIEmbeddingProvider p = new AzureOpenAIEmbeddingProvider(
                System.getenv("AZURE_OPENAI_ENDPOINT"),
                System.getenv("AZURE_OPENAI_KEY"),
                System.getenv().getOrDefault("AZURE_OPENAI_EMBEDDING_DEPLOYMENT", "text-embedding-3-small"));

        List<Float> v = p.embed("朝食は何時からですか？");

        assertThat(v).hasSize(1536);
        assertThat(v).anyMatch(x -> x != 0.0f);
    }
}
