package com.aicard.skb.provider.azureopenai;

import com.aicard.provider.api.ProviderException;
import com.aicard.skb.provider.EmbeddingProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Azure OpenAI embedding 适配器：调 text-embedding-3-small，返回 1536 维向量（与 schema vector(1536) 对齐）。
 * 用 REST（无额外 SDK 依赖）。endpoint 形如 https://{resource}.services.ai.azure.com。
 */
public class AzureOpenAIEmbeddingProvider implements EmbeddingProvider {

    private static final String API_VERSION = "2024-02-01";

    private final String endpoint;
    private final String apiKey;
    private final String deployment;
    private final RestTemplate rest = new RestTemplate();

    public AzureOpenAIEmbeddingProvider(String endpoint, String apiKey, String deployment) {
        this.endpoint = endpoint == null ? "" : endpoint.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.deployment = deployment;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Float> embed(String text) {
        String url = endpoint + "/openai/deployments/" + deployment
                + "/embeddings?api-version=" + API_VERSION;

        HttpHeaders headers = new HttpHeaders();
        headers.set("api-key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of("input", text, "model", deployment);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> resp = rest.postForEntity(url, request, Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.getBody().get("data");
            List<Object> embedding = (List<Object>) data.get(0).get("embedding");
            List<Float> floats = new ArrayList<>(embedding.size());
            for (Object o : embedding) {
                floats.add(((Number) o).floatValue());
            }
            return floats;
        } catch (Exception e) {
            throw new ProviderException("azure openai embedding failed", e);
        }
    }
}
