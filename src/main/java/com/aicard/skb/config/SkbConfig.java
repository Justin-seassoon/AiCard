package com.aicard.skb.config;

import com.aicard.skb.provider.EmbeddingProvider;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.provider.aibridgex.AibridgexLlmProvider;
import com.aicard.skb.provider.azureopenai.AzureOpenAIEmbeddingProvider;
import com.aicard.skb.provider.mock.MockEmbeddingProvider;
import com.aicard.skb.provider.mock.MockLlmProvider;
import com.aicard.skb.service.SkbService;
import com.aicard.skb.store.KnowledgeStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 员工知识库装配。embedding：设 AZURE_OPENAI_KEY 用真实 Azure OpenAI（text-embedding-3-small）；
 * LLM：设 AIBRIDGEX_KEY 用真实 aibridgex（deepseek）；均无 key 时回退 Mock。阈值经配置注入。
 */
@Configuration
public class SkbConfig {

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
            return new AzureOpenAIEmbeddingProvider(openAiEndpoint, openAiKey, embeddingDeployment);
        }
        return new MockEmbeddingProvider();
    }

    @Bean
    public LLMProvider llmProvider() {
        if (aibridgexKey != null && !aibridgexKey.isBlank()) {
            return new AibridgexLlmProvider(aibridgexBaseUrl, aibridgexKey, aibridgexModel);
        }
        return new MockLlmProvider();
    }

    @Bean
    public SkbService skbService(EmbeddingProvider embeddings, KnowledgeStore store, LLMProvider llm,
                                 @Value("${skb.theta-retr:0.55}") double thetaRetr,
                                 @Value("${skb.theta-check:0.6}") double thetaCheck) {
        return new SkbService(embeddings, store, llm, thetaRetr, thetaCheck);
    }
}
