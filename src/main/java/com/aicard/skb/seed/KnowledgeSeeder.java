package com.aicard.skb.seed;

import com.aicard.skb.model.Chunk;
import com.aicard.skb.model.Document;
import com.aicard.skb.provider.EmbeddingProvider;
import com.aicard.skb.store.KnowledgeStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 演示知识库灌库工具：读取日语版「一问一答」markdown，以「主题=document、问答对=chunk」写入。
 * 真实 embedding 后置，此处经 {@link EmbeddingProvider} 接口生成向量（Mock 或真实皆可）。
 *
 * <p>触发方式（等接好真实 Azure OpenAI 后）：
 * <pre>
 * java -jar app.jar \
 *   --skb.seed.enabled=true \
 *   --skb.seed.files=/app/便利店与免税店-日语版.md,/app/连锁酒店-日语版.md \
 *   --skb.seed.customer-id=1 --skb.seed.store-id=1
 * </pre>
 * 注意：灌库非幂等，重复执行会重复插入；正式演示前建议清空 document/chunk 表再灌。
 */
@Component
@ConditionalOnProperty(name = "skb.seed.enabled", havingValue = "true")
public class KnowledgeSeeder implements CommandLineRunner {

    private final KnowledgeStore store;
    private final EmbeddingProvider embeddings;

    @Value("${skb.seed.files:}")
    private String files;

    @Value("${skb.seed.customer-id:1}")
    private Long customerId;

    @Value("${skb.seed.store-id:1}")
    private Long storeId;

    @Value("${skb.seed.domain:skb}")
    private String domain;

    public KnowledgeSeeder(KnowledgeStore store, EmbeddingProvider embeddings) {
        this.store = store;
        this.embeddings = embeddings;
    }

    @Override
    public void run(String... args) throws IOException {
        if (files == null || files.isBlank()) {
            System.out.println("[seeder] 未配置 skb.seed.files，跳过灌库");
            return;
        }
        for (String file : files.split(",")) {
            seed(Path.of(file.trim()));
        }
    }

    private void seed(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<MarkdownQaParser.Topic> topics = MarkdownQaParser.parse(lines);
        int total = 0;
        for (MarkdownQaParser.Topic topic : topics) {
            Document doc = store.insertDocument(new Document(
                    null, customerId, storeId, topic.title(), "v1", "published", domain, Instant.now()));
            int idx = 0;
            for (MarkdownQaParser.Qa qa : topic.pairs()) {
                String text = "Q: " + qa.question() + " A: " + qa.answer();
                // embedding 用「问题」而非「问题+答案」整体，避免答案稀释问题向量的相似度
                store.insertChunk(new Chunk(null, doc.id(), customerId, storeId, idx++, text),
                        embeddings.embed(qa.question()));
            }
            total += topic.pairs().size();
        }
        System.out.println("[seeder] " + file.getFileName() + " 灌库完成：" + topics.size() + " 个主题 / " + total + " 条问答");
    }
}
