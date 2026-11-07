package com.aicard.skb.config;

import com.aicard.skb.cache.QaAnswerCache;
import com.aicard.skb.provider.EmbeddingProvider;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.provider.aibridgex.AibridgexLlmProvider;
import com.aicard.skb.provider.azureopenai.AzureOpenAIEmbeddingProvider;
import com.aicard.skb.provider.mock.MockEmbeddingProvider;
import com.aicard.skb.provider.mock.MockLlmProvider;
import com.aicard.skb.seed.KeywordExtractor;
import com.aicard.skb.service.SkbService;
import com.aicard.skb.store.KnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 员工知识库装配。embedding：设 AZURE_OPENAI_KEY 用真实 Azure OpenAI（text-embedding-3-small）；
 * LLM：设 AIBRIDGEX_KEY 用真实 aibridgex（deepseek）；均无 key 时回退 Mock。阈值经配置注入。
 */
@Configuration
public class SkbConfig {

    private static final Logger log = LoggerFactory.getLogger(SkbConfig.class);

    @Value("${azure.openai.endpoint:}")
    private String openAiEndpoint;

    @Value("${azure.openai.key:}")
    private String openAiKey;

    @Value("${azure.openai.embedding-deployment:text-embedding-3-small}")
    private String embeddingDeployment;

    @Value("${aibridgex.base-url:https://api.aibridgex.net/v1}")
    private String aibridgexBaseUrl;

    @Value("${aibridgex.key:}")
    private String aibridgexKey;

    @Value("${aibridgex.model:deepseek-v4-flash}")
    private String aibridgexModel;

    @Bean
    public EmbeddingProvider embeddingProvider() {
        if (openAiKey != null && !openAiKey.isBlank()) {
            AzureOpenAIEmbeddingProvider provider =
                    new AzureOpenAIEmbeddingProvider(openAiEndpoint, openAiKey, embeddingDeployment);
            warmUp(provider);
            return provider;
        }
        return new MockEmbeddingProvider();
    }

    /** 后台预热 embedding 连接（首个真实请求前建好 TLS/连接），失败不影响启动。 */
    private static void warmUp(EmbeddingProvider provider) {
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                provider.embed("ウォームアップ");
                log.info("embedding warm-up done in {}ms", System.currentTimeMillis() - t0);
            } catch (RuntimeException e) {
                log.warn("embedding warm-up failed: {}", e.getMessage());
            }
        }, "embedding-warmup");
        t.setDaemon(true);
        t.start();
    }

    @Bean
    public LLMProvider llmProvider() {
        if (aibridgexKey != null && !aibridgexKey.isBlank()) {
            return new AibridgexLlmProvider(aibridgexBaseUrl, aibridgexKey, aibridgexModel);
        }
        return new MockLlmProvider();
    }

    @Bean
    public KeywordExtractor keywordExtractor() {
        return new KeywordExtractor();
    }

    @Bean
    public QaAnswerCache qaAnswerCache(@Value("${skb.cache.enabled:true}") boolean enabled,
                                       @Value("${skb.cache.ttl-seconds:300}") long ttlSeconds,
                                       @Value("${skb.cache.max-entries:500}") int maxEntries) {
        return new QaAnswerCache(enabled, ttlSeconds, maxEntries);
    }

    @Bean
    public SkbService skbService(EmbeddingProvider embeddings, KnowledgeStore store, LLMProvider llm,
                                 KeywordExtractor keywordExtractor, QaAnswerCache cache,
                                 @Value("${skb.theta-retr:0.55}") double thetaRetr,
                                 @Value("${skb.theta-retr-cross-lingual:0.45}") double thetaRetrCrossLingual,
                                 @Value("${skb.theta-check:0.6}") double thetaCheck) {
        return new SkbService(embeddings, store, llm, keywordExtractor, cache, thetaRetr,
                thetaRetrCrossLingual, thetaCheck);
    }
}
