package com.aicard.gateway.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.AudioFrameCodec;
import com.aicard.gateway.protocol.InboundMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 精确时延测量：真实 Azure + 真实 WSS，测量「eou 尾包 → 首个 TTS 分片」的时延。
 * 覆盖 AUTO（LID 前置）与 FIXED（跳过 LID）两种模式，各 2 轮。仅设置 AZURE_SPEECH_KEY 时运行。
 */
@EnabledIfEnvironmentVariable(named = "AZURE_SPEECH_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LatencyMeasurementTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aicard").withUsername("aicard").withPassword("aicard");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    int port;

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void measureLatency() throws Exception {
        byte[] pcm = synthPcm("你好，请问洗手间在哪里？");

        long autoSum = 0, fixedSum = 0;
        System.out.println("=== 时延测量：eou 尾包 → 首个 TTS 分片 ===");
        for (int i = 0; i < 2; i++) {
            long ms = measure(pcm, "auto", null);
            autoSum += ms;
            System.out.println("  AUTO 第" + (i + 1) + "轮 = " + ms + " ms");
        }
        System.out.println("  AUTO 平均 = " + (autoSum / 2) + " ms");

        for (int i = 0; i < 2; i++) {
            long ms = measure(pcm, "fixed", "zh-ja");
            fixedSum += ms;
            System.out.println("  FIXED 第" + (i + 1) + "轮 = " + ms + " ms");
        }
        System.out.println("  FIXED 平均 = " + (fixedSum / 2) + " ms");
    }

    /** 建连 → session_init → 音频分片 → eou，测量 eou 到首个 TTS 分片的时延。 */
    private long measure(byte[] pcm, String mode, String langPair) throws Exception {
        CountDownLatch firstTts = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicLong firstTtsNanos = new AtomicLong();

        WebSocketHandler client = new AbstractWebSocketHandler() {
            @Override
            protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
                if (firstTts.getCount() > 0) {
                    firstTtsNanos.set(System.nanoTime());
                    firstTts.countDown();
                }
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                if (message.getPayload().contains("language_state")
                        || message.getPayload().contains("\"error\"")) {
                    done.countDown();
                }
            }
        };

        String url = "ws://localhost:" + port
                + "/ws?device_id=demo-device-001&token=demo-token-001&fw_version=0.1.0&proto_ver=1";
        WebSocketSession session = new StandardWebSocketClient().doHandshake(client, url).get(5, TimeUnit.SECONDS);

        String sessionId = "s" + System.nanoTime();
        InboundMessage.InboundMessageBuilder init = InboundMessage.builder()
                .type("session_init").sessionId(sessionId)
                .scope("translate").translationMode(mode).staffLanguage("ja-JP");
        if (langPair != null) {
            init.langPair(langPair);
        }
        session.sendMessage(new TextMessage(mapper.writeValueAsString(init.build())));

        int chunkSize = 8000;
        int seq = 0;
        for (int off = 0; off < pcm.length; off += chunkSize) {
            int len = Math.min(chunkSize, pcm.length - off);
            byte[] chunk = Arrays.copyOfRange(pcm, off, off + len);
            session.sendMessage(new BinaryMessage(AudioFrameCodec.encodeAudioChunk(
                    new AudioChunkFrame("t1", seq++, off, (byte) 1, (byte) 1, chunk))));
        }

        long eouNanos = System.nanoTime();
        session.sendMessage(new TextMessage(mapper.writeValueAsString(
                InboundMessage.builder().type("eou").sessionId(sessionId)
                        .turnId("t1").sourceSide("counterparty").lastSequence(seq - 1).build())));

        firstTts.await(30, TimeUnit.SECONDS);
        done.await(35, TimeUnit.SECONDS);
        session.close();

        return (firstTtsNanos.get() - eouNanos) / 1_000_000;
    }

    private static byte[] synthPcm(String text) throws Exception {
        SpeechConfig config = SpeechConfig.fromSubscription(
                System.getenv("AZURE_SPEECH_KEY"),
                System.getenv().getOrDefault("AZURE_SPEECH_REGION", "japaneast"));
        config.setSpeechSynthesisVoiceName("zh-CN-XiaoxiaoNeural");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SpeechSynthesizer syn = new SpeechSynthesizer(config, null)) {
            syn.Synthesizing.addEventListener((o, e) -> {
                byte[] a = e.getResult().getAudioData();
                if (a != null && a.length > 0) {
                    out.writeBytes(a);
                }
            });
            syn.SpeakTextAsync(text).get();
        }
        return out.toByteArray();
    }
}
