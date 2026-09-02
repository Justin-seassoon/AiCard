package com.aicard.gateway.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.AudioFrameCodec;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
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

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证：真实 WSS 握手鉴权 → session_init → 二进制音频分片 → eou → 收到
 * final_text / language_state / tts_audio / tts_end 回包。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GatewayWebSocketIntegrationTest {

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
    void fullTranslationRoundTrip() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);
        List<String> texts = new CopyOnWriteArrayList<>();
        List<byte[]> binaries = new CopyOnWriteArrayList<>();

        WebSocketHandler client = new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                texts.add(message.getPayload());
                latch.countDown();
            }

            @Override
            protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
                ByteBuffer buf = message.getPayload();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                binaries.add(bytes);
                latch.countDown();
            }
        };

        String url = "ws://localhost:" + port
                + "/ws?device_id=demo-device-001&token=demo-token-001&fw_version=0.1.0&proto_ver=1";
        WebSocketSession session = new StandardWebSocketClient()
                .doHandshake(client, url).get(5, TimeUnit.SECONDS);

        // session_init
        session.sendMessage(new TextMessage(mapper.writeValueAsString(
                InboundMessage.builder().type("session_init").sessionId("s1")
                        .scope("translate").translationMode("auto").staffLanguage("ja-JP").build())));

        // 二进制音频分片（0x01）
        AudioChunkFrame chunk = new AudioChunkFrame("t1", 0, 0L, (byte) 1, (byte) 1, new byte[]{1, 2, 3});
        session.sendMessage(new BinaryMessage(AudioFrameCodec.encodeAudioChunk(chunk)));

        // eou
        session.sendMessage(new TextMessage(mapper.writeValueAsString(
                InboundMessage.builder().type("eou").sessionId("s1").turnId("t1")
                        .sourceSide("counterparty").lastSequence(0).build())));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // 验证回包：language_state + tts_end（文本）+ tts_audio（二进制）
        assertThat(texts).anyMatch(t -> t.contains("\"language_state\""));
        assertThat(texts).anyMatch(t -> t.contains("\"tts_end\""));
        assertThat(binaries).hasSize(1);
        TtsAudioFrame tts = AudioFrameCodec.decodeTtsAudio(binaries.get(0));
        assertThat(tts.turnId()).isEqualTo("t1");
        assertThat(tts.audio()).isNotEmpty();

        session.close();
    }
}
