package com.aicard.gateway.push;

import com.aicard.gateway.protocol.AudioFrameCodec;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.state.DeviceConnectionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Arrays;

/**
 * 云端主动推送器：把「文本控制消息 + TTS 音频」下发到在线设备的 WebSocket 连接。
 * 广播与运营通知共用此底层发送逻辑（不重写 socket / TTS 分片）。
 */
@Component
public class DevicePusher {

    private static final int CHUNK_SIZE = 8000;

    private final DeviceConnectionRegistry connections;
    private final ObjectMapper mapper;

    public DevicePusher(DeviceConnectionRegistry connections, ObjectMapper mapper) {
        this.connections = connections;
        this.mapper = mapper;
    }

    /** 向指定设备推送；设备离线返回 false。 */
    public boolean sendToDevice(String deviceId, OutboundMessage text, byte[] tts, String ttsTurnId) {
        return connections.findOnline(deviceId)
                .map(session -> send(session, text, tts, ttsTurnId))
                .orElse(false);
    }

    /** 向指定会话推送；失败返回 false（不抛异常）。 */
    public boolean send(WebSocketSession session, OutboundMessage text, byte[] tts, String ttsTurnId) {
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(text)));
            for (int off = 0; off < tts.length; off += CHUNK_SIZE) {
                int len = Math.min(CHUNK_SIZE, tts.length - off);
                session.sendMessage(new BinaryMessage(AudioFrameCodec.encodeTtsAudio(
                        new TtsAudioFrame(ttsTurnId, Arrays.copyOfRange(tts, off, off + len)))));
            }
            session.sendMessage(new TextMessage(mapper.writeValueAsString(OutboundMessage.builder()
                    .type("tts_end").turnId(ttsTurnId).build())));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
