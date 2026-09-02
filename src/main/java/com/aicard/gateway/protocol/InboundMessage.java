package com.aicard.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

/**
 * 上行 JSON 控制消息（文本帧）。字段 snake_case 由全局 Jackson 命名策略处理，
 * 见《端云通信接口规范》§3。可空字段序列化时省略。
 *
 * 消息 type：session_init | eou | stop_tts | sleep_notice | button_event |
 *            playback_event | scope_change | translation_mode_change |
 *            headset_event | config_pull
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InboundMessage(
        String type,
        String sessionId,
        String turnId,
        // eou
        String sourceSide,        // wearer | counterparty | uncertain
        Integer lastSequence,     // 该 turn 最后分片序号（收齐校验）
        Long utteranceDurationMs, // 有效语音时长
        String inputSource,       // badge_near | badge_far | headset_mic | uncertain
        // stop_tts
        String reason,            // voice | button
        // button_event
        String button,
        // playback_event
        String event,             // started | completed
        // scope_change
        String scope,             // translate | staff_qa
        // translation_mode_change
        String translationMode,   // auto | fixed
        String langPair,
        // headset_event
        String headsetState,
        // session_init
        String staffLanguage,
        String wifiBand,
        String networkState,
        Integer configVersion,
        List<String> candidates
) {}
