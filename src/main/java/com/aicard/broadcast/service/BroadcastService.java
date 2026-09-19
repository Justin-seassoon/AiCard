package com.aicard.broadcast.service;

import com.aicard.broadcast.domain.Broadcast;
import com.aicard.broadcast.domain.BroadcastDelivery;
import com.aicard.broadcast.repository.BroadcastDeliveryRepository;
import com.aicard.broadcast.repository.BroadcastRepository;
import com.aicard.broadcast.repository.DeviceGroupMemberRepository;
import com.aicard.broadcast.repository.DeviceGroupRepository;
import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.gateway.protocol.AudioFrameCodec;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.state.DeviceConnectionRegistry;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.skb.provider.LLMProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 广播编排：源文本 → 按成员目标语言去重翻译 + TTS → 落投递记录 → 在线设备实时推、离线设备留待补拉。
 * 同语言只翻译/TTS 一次（省成本）；离线补拉时按已存译文重新合成 TTS（不落音频存储）。
 */
@Service
public class BroadcastService {

    private static final int CHUNK_SIZE = 8000;
    private static final String DEFAULT_LANG = "ja-JP";

    private final DeviceGroupRepository groups;
    private final DeviceGroupMemberRepository members;
    private final BroadcastRepository broadcasts;
    private final BroadcastDeliveryRepository deliveries;
    private final DeviceRepository devices;
    private final LLMProvider llm;
    private final SpeechProvider speech;
    private final DeviceConnectionRegistry connections;
    private final ObjectMapper mapper;

    public BroadcastService(DeviceGroupRepository groups, DeviceGroupMemberRepository members,
                            BroadcastRepository broadcasts, BroadcastDeliveryRepository deliveries,
                            DeviceRepository devices, LLMProvider llm, SpeechProvider speech,
                            DeviceConnectionRegistry connections, ObjectMapper mapper) {
        this.groups = groups;
        this.members = members;
        this.broadcasts = broadcasts;
        this.deliveries = deliveries;
        this.devices = devices;
        this.llm = llm;
        this.speech = speech;
        this.connections = connections;
        this.mapper = mapper;
    }

    public Broadcast broadcast(Long groupId, String sourceText, String sourceLanguage,
                               Long customerId, Long storeId) {
        groups.findByIdAndCustomerIdAndStoreId(groupId, customerId, storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "group not found"));

        List<Device> memberDevices = members.findByGroupId(groupId).stream()
                .map(m -> devices.findByDeviceId(m.getDeviceId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        if (memberDevices.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "group has no members");
        }

        Map<String, List<Device>> byLang = memberDevices.stream()
                .collect(Collectors.groupingBy(d -> lang(d.getStaffLanguage())));

        Broadcast broadcast = broadcasts.save(Broadcast.builder()
                .groupId(groupId).sourceText(sourceText).sourceLanguage(sourceLanguage)
                .createdAt(Instant.now()).build());

        Map<String, String> translated = new HashMap<>();
        Map<String, byte[]> tts = new HashMap<>();
        for (String targetLang : byLang.keySet()) {
            String text = targetLang.equals(sourceLanguage) ? sourceText : llm.translate(sourceText, targetLang);
            byte[] audio = speech.synthesize(text, targetLang);
            translated.put(targetLang, text);
            tts.put(targetLang, audio);
        }

        for (Device d : memberDevices) {
            String targetLang = lang(d.getStaffLanguage());
            BroadcastDelivery delivery = BroadcastDelivery.builder()
                    .broadcastId(broadcast.getId()).deviceId(d.getDeviceId())
                    .translatedText(translated.get(targetLang)).targetLanguage(targetLang)
                    .build();
            connections.findOnline(d.getDeviceId()).ifPresent(session -> {
                if (push(session, broadcast.getId(), translated.get(targetLang), targetLang, tts.get(targetLang))) {
                    delivery.setDeliveredAt(Instant.now());
                }
            });
            deliveries.save(delivery);
        }
        return broadcast;
    }

    /** 设备按 OK 键回执（broadcast_ack），标记确认时间。 */
    public void ack(Long broadcastId, String deviceId) {
        deliveries.findByBroadcastIdAndDeviceId(broadcastId, deviceId).ifPresent(d -> {
            if (d.getAckAt() == null) {
                d.setAckAt(Instant.now());
                deliveries.save(d);
            }
        });
    }

    /** 设备重连后补拉未投递的广播（按已存译文重新合成 TTS，逐条推送）。 */
    public void deliverPending(String deviceId) {
        List<BroadcastDelivery> pending = deliveries.findByDeviceIdAndDeliveredAtIsNull(deviceId);
        if (pending.isEmpty()) {
            return;
        }
        connections.findOnline(deviceId).ifPresent(session -> {
            for (BroadcastDelivery d : pending) {
                try {
                    byte[] audio = speech.synthesize(d.getTranslatedText(), d.getTargetLanguage());
                    if (push(session, d.getBroadcastId(), d.getTranslatedText(), d.getTargetLanguage(), audio)) {
                        d.setDeliveredAt(Instant.now());
                        deliveries.save(d);
                    }
                } catch (RuntimeException e) {
                    // 单条补拉失败不影响其余
                }
            }
        });
    }

    private boolean push(WebSocketSession session, Long broadcastId, String text, String targetLang, byte[] tts) {
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(OutboundMessage.builder()
                    .type("broadcast").broadcastId(broadcastId).text(text).targetLanguage(targetLang).build())));
            for (int off = 0; off < tts.length; off += CHUNK_SIZE) {
                int len = Math.min(CHUNK_SIZE, tts.length - off);
                session.sendMessage(new BinaryMessage(AudioFrameCodec.encodeTtsAudio(
                        new TtsAudioFrame("bc" + broadcastId, Arrays.copyOfRange(tts, off, off + len)))));
            }
            session.sendMessage(new TextMessage(mapper.writeValueAsString(OutboundMessage.builder()
                    .type("tts_end").turnId("bc" + broadcastId).build())));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String lang(String staffLanguage) {
        return staffLanguage != null && !staffLanguage.isBlank() ? staffLanguage : DEFAULT_LANG;
    }
}
