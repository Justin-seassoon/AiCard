package com.aicard.gateway.session;

import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class SessionManager {

    private final ConcurrentHashMap<String, SessionContext> sessions = new ConcurrentHashMap<>();

    public SessionContext create(String sessionId, String deviceId, Long customerId, Long storeId,
                                 String scope, String translationMode, String staffLanguage,
                                 Consumer<OutboundMessage> textSender, Consumer<TtsAudioFrame> binarySender) {
        SessionContext ctx = new SessionContext(sessionId, deviceId, customerId, storeId,
                scope, translationMode, staffLanguage, textSender, binarySender);
        sessions.put(sessionId, ctx);
        return ctx;
    }

    public Optional<SessionContext> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }
}
