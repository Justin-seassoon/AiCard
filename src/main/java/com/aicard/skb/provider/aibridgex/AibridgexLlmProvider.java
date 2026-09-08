package com.aicard.skb.provider.aibridgex;

import com.aicard.provider.api.ProviderException;
import com.aicard.skb.model.LlmResult;
import com.aicard.skb.model.RetrievedChunk;
import com.aicard.skb.provider.LLMProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * aibridgex（OpenAI 兼容网关）LLM 适配器：调 deepseek 模型做受限生成。
 * 通过 system prompt 约束：只基于命中片段回答、引用必须选自片段 chunk_id、输出 JSON。
 */
public class AibridgexLlmProvider implements LLMProvider {

    private static final String SYSTEM_PROMPT = """
            あなたはサービス業店舗の社員向けナレッジベースの回答アシスタントです。
            提供された「知識断片」だけを使って、社員の質問に簡潔に答えてください。
            規則：
            1. 「知識断片」以外の知識で答えないでください。断片に答えがない場合は answer を「知識ベースに明確な記載がありません」にする。
            2. 各断片には番号（chunk_id）があります。
            3. 出力は JSON オブジェクト 1 つだけにしてください（他の文字は一切出さない）。
            4. 形式：{"answer":"回答","cited_chunk_ids":[引用した断片の chunk_id]}
            5. cited_chunk_ids には実際に存在する chunk_id だけを入れてください。使わなかった断片は入れない。
            6. 回答は日本語で、一文程度にしてください。
            """;

    private static final String TRANSLATE_PROMPT = """
            あなたは翻訳アシスタントです。与えられたテキストを指定された言語に翻訳してください。
            翻訳結果だけを出力してください（説明や引用符は付けない）。
            """;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    public AibridgexLlmProvider(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.rest = buildRestTemplate();
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }

    @Override
    public LlmResult generate(String question, List<RetrievedChunk> context) {
        String url = baseUrl + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", buildUserPrompt(question, context))));

        try {
            ResponseEntity<Map> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> respBody = resp.getBody();
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");
            LlmResult result = parse(content);
            return result != null ? result : new LlmResult("", List.of());
        } catch (Exception e) {
            throw new ProviderException("aibridgex llm failed", e);
        }
    }

    @Override
    public String translate(String text, String targetLang) {
        String url = baseUrl + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", TRANSLATE_PROMPT),
                        Map.of("role", "user", "content", "翻訳先言語: " + targetLang + "\nテキスト: " + text)));

        try {
            ResponseEntity<Map> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> respBody = resp.getBody();
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            throw new ProviderException("aibridgex translate failed", e);
        }
    }

    private String buildUserPrompt(String question, List<RetrievedChunk> context) {
        StringBuilder sb = new StringBuilder("知識断片：\n");
        for (RetrievedChunk c : context) {
            sb.append("[chunk_id=").append(c.chunkId()).append("] ").append(c.text()).append('\n');
        }
        sb.append("\n社員の質問：").append(question);
        return sb.toString();
    }

    /** 从 LLM 输出里容错提取 JSON 并解析成 LlmResult；解析失败返回 null（由调用方走 noMatch 兜底）。 */
    private LlmResult parse(String content) {
        String json = extractJson(content);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            String answer = node.path("answer").asText("");
            List<Long> cited = new ArrayList<>();
            for (JsonNode id : node.path("cited_chunk_ids")) {
                if (id.isNumber()) {
                    cited.add(id.asLong());
                }
            }
            return new LlmResult(answer, cited);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String t = content.trim();
        if (t.startsWith("```")) {
            int start = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (start >= 0 && end > start) {
                t = t.substring(start + 1, end).trim();
            }
        }
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        if (a >= 0 && b > a) {
            return t.substring(a, b + 1);
        }
        return null;
    }
}
