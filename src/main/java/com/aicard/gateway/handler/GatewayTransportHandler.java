package com.aicard.gateway.handler;

import com.aicard.common.domain.Device;
import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import com.aicard.gateway.auth.GatewayHandshakeInterceptor;
import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.AudioFrameCodec;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import com.aicard.gateway.session.SessionManager;
import com.aicard.gateway.state.DeviceStateTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;

/**
 * 传输桥接：一个 WSS 连接对应一个会话上下文（连接即会话，二进制音频帧不带
 * session_id，靠连接关联）。负责二进制/文本帧编解码收发、session_init 建会话、
 * 租户上下文写入与清理、设备状态标记。
 */
@Component
public class GatewayTransportHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayTransportHandler.class);
    private static final String ATTR_SESSION = "sessionContext";

    private final ObjectMapper mapper;
    private final SessionManager sessions;
    private final GatewayWebSocketHandler router;
    private final DeviceStateTracker states;

    public GatewayTransportHandler(ObjectMapper mapper, SessionManager sessions,
                                   GatewayWebSocketHandler router, DeviceStateTracker states) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.router = router;
        this.states = states;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Device device = (Device) session.getAttributes().get(GatewayHandshakeInterceptor.ATTR_DEVICE);
        if (device != null) {
            states.markOnline(device.getDeviceId());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        try {
            AudioChunkFrame frame = AudioFrameCodec.decodeAudioChunk(toByteArray(message.getPayload()));
            SessionContext ctx = requireSession(session);
            withTenant(ctx, () -> router.handleAudio(ctx, frame));
        } catch (Exception e) {
            log.warn("reject binary frame: {}", e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            InboundMessage msg = mapper.readValue(message.getPayload(), InboundMessage.class);
            if ("session_init".equals(msg.type())) {
                initSession(session, msg);
                return;
            }
            SessionContext ctx = requireSession(session);
            if ("sleep_notice".equals(msg.type())) {
                states.markSleep(ctx.deviceId());
                return;
            }
            withTenant(ctx, () -> router.handleControl(ctx, msg));
        } catch (Exception e) {
            log.warn("reject text frame: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionContext ctx = (SessionContext) session.getAttributes().get(ATTR_SESSION);
        if (ctx != null) {
            sessions.remove(ctx.sessionId());
            states.markSleep(ctx.deviceId());
        }
    }

    private void initSession(WebSocketSession session, InboundMessage msg) {
        Device device = (Device) session.getAttributes().get(GatewayHandshakeInterceptor.ATTR_DEVICE);
        if (device == null) {
            throw new IllegalStateException("device not authenticated");
        }
        String scope = msg.scope() != null ? msg.scope() : "translate";
        String translationMode = msg.translationMode() != null ? msg.translationMode() : "fixed";
        String staffLanguage = msg.staffLanguage() != null ? msg.staffLanguage() : "ja-JP";
        SessionContext ctx = sessions.create(msg.sessionId(), device.getDeviceId(),
                device.getCustomerId(), device.getStoreId(), scope, translationMode, staffLanguage,
                out -> sendText(session, out),
                frame -> sendBinary(session, frame));
        ctx.langPair(msg.langPair() != null ? msg.langPair() : "zh-ja");
        session.getAttributes().put(ATTR_SESSION, ctx);
        states.markOnline(device.getDeviceId());
    }

    private SessionContext requireSession(WebSocketSession session) {
        SessionContext ctx = (SessionContext) session.getAttributes().get(ATTR_SESSION);
        if (ctx == null) {
            throw new IllegalStateException("session not initialized (session_init required first)");
        }
        return ctx;
    }

    private void withTenant(SessionContext ctx, Runnable action) {
        TenantContext.set(Tenant.of(ctx.customerId(), ctx.storeId()));
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    private void sendText(WebSocketSession session, OutboundMessage out) {
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(out)));
        } catch (Exception e) {
            throw new IllegalStateException("downstream text send failed", e);
        }
    }

    private void sendBinary(WebSocketSession session, TtsAudioFrame frame) {
        try {
            session.sendMessage(new BinaryMessage(AudioFrameCodec.encodeTtsAudio(frame)));
        } catch (Exception e) {
            throw new IllegalStateException("downstream binary send failed", e);
        }
    }

    private static byte[] toByteArray(ByteBuffer buf) {
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return bytes;
    }
}
