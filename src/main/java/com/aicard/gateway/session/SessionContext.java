package com.aicard.gateway.session;

import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;

import java.util.function.Consumer;

/**
 * 一次翻译会话的上下文。会话级可变状态（scope / translationMode / turnId）在此维护；
 * 软锁定语言记忆（visitor_language）由 P4 的 TranslationHandler 维护（TranslationSessionState）。
 */
public class SessionContext {
    private final String sessionId;
    private final String deviceId;
    private final Long customerId;
    private final Long storeId;
    private final String staffLanguage;
    private final Consumer<OutboundMessage> textSender;
    private final Consumer<TtsAudioFrame> binarySender;

    private volatile String scope;            // translate | staff_qa（会话业务域）
    private volatile String translationMode;  // auto | fixed（语言对模式）
    private volatile String langPair;         // fixed 模式的 lang_pair（游客侧-员工侧，如 zh-ja）
    private volatile String turnId;

    public SessionContext(String sessionId, String deviceId, Long customerId, Long storeId,
                          String scope, String translationMode, String staffLanguage,
                          Consumer<OutboundMessage> textSender, Consumer<TtsAudioFrame> binarySender) {
        this.sessionId = sessionId;
        this.deviceId = deviceId;
        this.customerId = customerId;
        this.storeId = storeId;
        this.scope = scope;
        this.translationMode = translationMode;
        this.staffLanguage = staffLanguage;
        this.textSender = textSender;
        this.binarySender = binarySender;
    }

    public void sendText(OutboundMessage msg) { textSender.accept(msg); }
    public void sendTtsAudio(TtsAudioFrame frame) { binarySender.accept(frame); }

    public String sessionId() { return sessionId; }
    public String deviceId() { return deviceId; }
    public Long customerId() { return customerId; }
    public Long storeId() { return storeId; }
    public String staffLanguage() { return staffLanguage; }

    public String scope() { return scope; }
    public void scope(String scope) { this.scope = scope; }

    public String translationMode() { return translationMode; }
    public void translationMode(String translationMode) { this.translationMode = translationMode; }

    public String langPair() { return langPair; }
    public void langPair(String langPair) { this.langPair = langPair; }

    public String turnId() { return turnId; }
    public void turnId(String turnId) { this.turnId = turnId; }
}
