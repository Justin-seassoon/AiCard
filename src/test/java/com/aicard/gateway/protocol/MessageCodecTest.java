package com.aicard.gateway.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageCodecTest {

    // 与运行时一致：全局 snake_case（application.yml spring.jackson.property-naming-strategy）
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void serializesInboundWithSnakeCase() throws Exception {
        InboundMessage msg = InboundMessage.builder()
                .type("eou").sessionId("s1").turnId("t1")
                .sourceSide("counterparty").lastSequence(23).utteranceDurationMs(1500L)
                .build();
        String json = mapper.writeValueAsString(msg);

        assertThat(json).contains("\"session_id\":\"s1\"");
        assertThat(json).contains("\"turn_id\":\"t1\"");
        assertThat(json).contains("\"source_side\":\"counterparty\"");
        assertThat(json).contains("\"last_sequence\":23");
        assertThat(json).contains("\"utterance_duration_ms\":1500");
    }

    @Test
    void roundTripsInboundControl() throws Exception {
        InboundMessage msg = InboundMessage.builder()
                .type("session_init").sessionId("s1").scope("translate")
                .translationMode("auto").staffLanguage("ja-JP").configVersion(123)
                .candidates(List.of("ja-JP", "zh-CN"))
                .build();
        String json = mapper.writeValueAsString(msg);
        InboundMessage back = mapper.readValue(json, InboundMessage.class);

        assertThat(back.type()).isEqualTo("session_init");
        assertThat(back.scope()).isEqualTo("translate");
        assertThat(back.translationMode()).isEqualTo("auto");
        assertThat(back.staffLanguage()).isEqualTo("ja-JP");
        assertThat(back.candidates()).containsExactly("ja-JP", "zh-CN");
    }

    @Test
    void outboundOmitsNullFieldsAndUsesSnakeCase() throws Exception {
        OutboundMessage msg = OutboundMessage.builder()
                .type("error").sessionId("s1").turnId("t1")
                .code("E_LID_LOW").message("请再说一遍").fallbackAction("ask_repeat")
                .build();
        String json = mapper.writeValueAsString(msg);

        assertThat(json).doesNotContain("text");
        assertThat(json).doesNotContain("confidence");
        assertThat(json).contains("\"fallback_action\":\"ask_repeat\"");
        assertThat(json).contains("\"E_LID_LOW\"");
    }
}
