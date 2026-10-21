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

    private static final String DRAFT_PROMPT = """
            あなたはサービス業店舗の社内ナレッジ整備アシスタントです。
            社員の質問に答えられなかった項目について、候補となる回答の下書きを作成してください。
            規則：
            1. 「参考資料」があればそれを根拠にしてください。
            2. 参考資料に無い事項は断定的に書かず、確認が必要な箇所は「※要確認」と明記してください。
            3. 出力は回答本文のみ（JSON・見出し・引用符・前置きは不要）。日本語で簡潔に。
            """;

    private static final String DRAFT_DOC_PROMPT = """
            あなたは日本の企業の総務担当者を支援するアシスタントです。
            指示に従って、社内文書の下書き（本文）を作成してください。
            規則：
            1. です・ます調の丁寧なビジネス文体で書いてください。
            2. 指示にない事実を勝手に作らないでください。不明な点は「※要確認」と明記してください。
            3. 本文のみを出力してください（見出し・前置き・引用符・JSONは不要）。
            """;

    private static final String PLAN_PROMPT = """
            あなたは業務エージェントのプランナーです。
            目標・利用可能なツール一覧・これまでの実行履歴から、次に呼ぶツールを 1 つだけ選んでください。
            規則：
            1. 出力は JSON オブジェクト 1 つだけ（他の文字は一切出さない）。
            2. 形式：{"tool":"ツール名","args":{...}} または {"done":true}
            3. 目標が達成されたら {"done":true} を出力してください。
            4. 一覧にあるツール名だけを使ってください。
            5. args は各ツールの args ヒントに従ってください（必須項目を必ず入れる）。
            6. 同じツールを意味なく繰り返さないでください。
            """;

    private static final String SUMMARY_PROMPT = """
            あなたは業務アシスタントです。
            実行結果だけを根拠に、ユーザーの目標に対する回答を日本語で簡潔に述べてください。
            規則：
            1. 実行結果に含まれる数値・件数・一覧を必ずそのまま使ってください。
               例：usage_summary の turnCount は「翻訳件数」です。
            2. 実行結果に無い内容を推測しないでください。
            3. 1〜3 文で簡潔に。
            4. 前置き・見出し・引用符は不要。本文のみを出力。
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

    @Override
    public String draftAnswer(String question, List<RetrievedChunk> related) {
        String url = baseUrl + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        StringBuilder ctx = new StringBuilder("参考資料：\n");
        for (RetrievedChunk c : related) {
            ctx.append("- ").append(c.text()).append('\n');
        }
        ctx.append("\n答えられなかった質問：").append(question);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", DRAFT_PROMPT),
                        Map.of("role", "user", "content", ctx.toString())));

        try {
            ResponseEntity<Map> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> respBody = resp.getBody();
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            throw new ProviderException("aibridgex draft failed", e);
        }
    }

    @Override
    public String draftDocument(String instruction) {
        String url = baseUrl + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", DRAFT_DOC_PROMPT),
                        Map.of("role", "user", "content", instruction)));

        try {
            ResponseEntity<Map> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> respBody = resp.getBody();
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            throw new ProviderException("aibridgex draftDocument failed", e);
        }
    }

    @Override
    public PlanStep planStep(String goal, List<String> toolSpecs, List<String> history) {
        String url = baseUrl + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String user = "目標：" + goal
                + "\n利用可能なツール：\n- " + String.join("\n- ", toolSpecs)
                + "\n実行履歴：\n- " + (history.isEmpty() ? "（なし）" : String.join("\n- ", history));

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", PLAN_PROMPT),
                        Map.of("role", "user", "content", user)));

        try {
            ResponseEntity<Map> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> respBody = resp.getBody();
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return parsePlan((String) message.get("content"));
        } catch (Exception e) {
            throw new ProviderException("aibridgex planStep failed", e);
        }
    }

    /** 容错解析规划输出；**解析失败一律抛错**（用于区分「模型说完成」与「模型输出坏了」）。 */
    private PlanStep parsePlan(String content) {
        String json = extractJson(content);
        if (json == null) {
            throw new ProviderException("plan: model output has no JSON");
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (node.path("done").asBoolean(false)) {
                return PlanStep.finished();
            }
            String tool = node.path("tool").asText("");
            if (tool.isBlank()) {
                throw new ProviderException("plan: missing 'tool' (and not done)");
            }
            String args = node.has("args") ? node.get("args").toString() : "{}";
            return PlanStep.tool(tool, args);
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("plan: unparseable model output", e);
        }
    }

    @Override
    public String summarize(String instruction) {
        String url = baseUrl + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", SUMMARY_PROMPT),
                        Map.of("role", "user", "content", instruction)));

        try {
            ResponseEntity<Map> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> respBody = resp.getBody();
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            throw new ProviderException("aibridgex summarize failed", e);
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
