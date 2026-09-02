package com.aicard.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * 下行 JSON 控制消息（文本帧）。字段 snake_case，可空字段序列化时省略。
 * 见《端云通信接口规范》§4。
 *
 * 消息 type：tts_end | error | final_text | partial_text | answer |
 *            language_state | config_update | output_route
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutboundMessage(
        String type,
        String sessionId,
        String turnId,
        String text,
        // error
        String code,
        String message,
        String fallbackAction,
        // answer
        List<String> sourceRefs,
        // language_state
        String visitorLanguage,
        String lidLanguage,
        Double confidence,
        String state,            // unknown | locked | updating | reset | conflict
        // config_update
        Integer configVersion,
        Map<String, Object> config,
        // output_route
        String route
) {}
