package com.aicard.gateway.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.AudioFrameCodec;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.provider.azure.AzureSpeechProvider;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端真实闭环：真实 Azure（TTS 生成中文语音 → 语音翻译为日文）+ 真实 WSS。
 * 仅当设置 AZURE_SPEECH_KEY 时运行。
 */
@EnabledIfEnvironmentVariable(named = "AZURE_SPEECH_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GatewayAzureEndToEndTest {

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
    void realTranslationRoundTrip() throws Exception {
        // 1) 用 SDK 直接 TTS 生成一段中文语音，作为设备上传的"游客说话"
        byte[] pcm = synthPcm("你好，请问洗手间在哪里？");
        System.out.println("[e2e] 准备上传的中文语音 PCM 字节数 = " + pcm.length);

        // 2) 走真实 WSS 链路
        CountDownLatch done = new CountDownLatch(1);
        List<String> texts = new CopyOnWriteArrayList<>();
        List<byte[]> binaries = new CopyOnWriteArrayList<>();

        WebSocketHandler client = new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                texts.add(message.getPayload());
                if (message.getPayload().contains("language_state")) {
                    done.countDown(); // language_state 是最后一条，此时 TTS 分片与 tts_end 均已到达
                }
            }

            @Override
            protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
                ByteBuffer buf = message.getPayload();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                binaries.add(bytes);
            }
        };

        String url = "ws://localhost:" + port
                + "/ws?device_id=demo-device-001&token=demo-token-001&fw_version=0.1.0&proto_ver=1";
        WebSocketSession session = new StandardWebSocketClient()
                .doHandshake(client, url).get(5, TimeUnit.SECONDS);

        session.sendMessage(new TextMessage(mapper.writeValueAsString(
                InboundMessage.builder().type("session_init").sessionId("s1")
                        .scope("translate").translationMode("auto").staffLanguage("ja-JP").build())));

        // 分片上传（单帧 ≤8KB，见接口规范 §9）
        int chunkSize = 8000;
        int seq = 0;
        for (int off = 0; off < pcm.length; off += chunkSize) {
            int len = Math.min(chunkSize, pcm.length - off);
            byte[] chunk = Arrays.copyOfRange(pcm, off, off + len);
            session.sendMessage(new BinaryMessage(AudioFrameCodec.encodeAudioChunk(
                    new AudioChunkFrame("t1", seq++, off, (byte) 1, (byte) 1, chunk))));
        }

        session.sendMessage(new TextMessage(mapper.writeValueAsString(
                InboundMessage.builder().type("eou").sessionId("s1").turnId("t1")
                        .sourceSide("counterparty").lastSequence(seq - 1).build())));

        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

        System.out.println("[e2e] 收到文本回包 = " + texts);
        assertThat(texts).anyMatch(t -> t.contains("\"language_state\"") && t.contains("zh-CN"));
        assertThat(texts).anyMatch(t -> t.contains("\"tts_end\""));
        assertThat(binaries).isNotEmpty();
        TtsAudioFrame first = AudioFrameCodec.decodeTtsAudio(binaries.get(0));
        assertThat(first.turnId()).isEqualTo("t1");
        int totalTts = binaries.stream().mapToInt(b -> b.length).sum();
        System.out.println("[e2e] 收到 TTS 分片数 = " + binaries.size() + " / 总字节 = " + totalTts);

        session.close();
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
