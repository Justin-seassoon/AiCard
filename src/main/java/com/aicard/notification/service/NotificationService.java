package com.aicard.notification.service;

import com.aicard.broadcast.repository.DeviceGroupMemberRepository;
import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.push.DevicePusher;
import com.aicard.notification.domain.NotificationDelivery;
import com.aicard.notification.domain.OperationNotification;
import com.aicard.notification.dto.NotificationRequest;
import com.aicard.notification.repository.NotificationDeliveryRepository;
import com.aicard.notification.repository.OperationNotificationRepository;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.skb.provider.LLMProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 运营通知编排：配置文案 → 展开目标设备 → 按语言去重翻译 + TTS → 投递/回执，离线待补拉。
 * 推送复用 {@link DevicePusher}（与广播共用），消息仍以 type="broadcast" 下发（零端侧改动）。
 */
@Service
public class NotificationService {

    private static final String DEFAULT_LANG = "ja-JP";

    private final OperationNotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final DeviceRepository devices;
    private final DeviceGroupMemberRepository members;
    private final LLMProvider llm;
    private final SpeechProvider speech;
    private final DevicePusher pusher;

    public NotificationService(OperationNotificationRepository notifications,
                               NotificationDeliveryRepository deliveries,
                               DeviceRepository devices, DeviceGroupMemberRepository members,
                               LLMProvider llm, SpeechProvider speech, DevicePusher pusher) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.devices = devices;
        this.members = members;
        this.llm = llm;
        this.speech = speech;
        this.pusher = pusher;
    }

    public OperationNotification create(Long customerId, Long storeId, NotificationRequest req) {
        return notifications.save(OperationNotification.builder()
                .customerId(customerId).storeId(storeId)
                .title(req.title()).content(req.content()).sourceLanguage(req.sourceLanguage())
                .targetType(req.targetType()).targetId(req.targetId())
                .triggerType(req.triggerType()).scheduleAt(req.scheduleAt()).cronExpr(req.cronExpr())
                .status(OperationNotification.STATUS_SCHEDULED).createdAt(Instant.now()).build());
    }

    public List<OperationNotification> list(Long customerId, Long storeId) {
        return notifications.findByCustomerIdAndStoreId(customerId, storeId);
    }

    public OperationNotification cancel(Long id, Long customerId, Long storeId) {
        OperationNotification n = notifications.findByIdAndCustomerIdAndStoreId(id, customerId, storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "notification not found"));
        n.setStatus(OperationNotification.STATUS_CANCELLED);
        return notifications.save(n);
    }

    public List<NotificationDelivery> listDeliveries(Long notificationId) {
        return deliveries.findByNotificationId(notificationId);
    }

    /** 派发所有到期的定时通知（once 型），返回派发条数。 */
    public int dispatchDue(Long customerId, Long storeId) {
        List<OperationNotification> due = notifications
                .findByCustomerIdAndStoreIdAndStatusAndScheduleAtLessThanEqual(
                        customerId, storeId, OperationNotification.STATUS_SCHEDULED, Instant.now());
        for (OperationNotification n : due) {
            dispatch(n);
        }
        return due.size();
    }

    /** 设备重连后补拉未投递的通知（按已存译文重新合成 TTS）。 */
    public void deliverPending(String deviceId) {
        List<NotificationDelivery> pending = deliveries.findByDeviceIdAndDeliveredAtIsNull(deviceId);
        for (NotificationDelivery d : pending) {
            try {
                byte[] audio = speech.synthesize(d.getTranslatedText(), d.getTargetLanguage());
                if (pusher.sendToDevice(deviceId, message(d.getNotificationId(), d.getTranslatedText(),
                        d.getTargetLanguage()), audio, "bc" + d.getNotificationId())) {
                    d.setDeliveredAt(Instant.now());
                    deliveries.save(d);
                }
            } catch (RuntimeException e) {
                // 单条补拉失败不影响其余
            }
        }
    }

    private void dispatch(OperationNotification n) {
        List<Device> targets = resolveTargets(n);
        if (targets.isEmpty()) {
            n.setStatus(OperationNotification.STATUS_SENT);
            notifications.save(n);
            return;
        }
        Map<String, List<Device>> byLang = targets.stream()
                .collect(Collectors.groupingBy(d -> lang(d.getStaffLanguage())));
        Map<String, String> translated = new HashMap<>();
        Map<String, byte[]> tts = new HashMap<>();
        for (String targetLang : byLang.keySet()) {
            String text = targetLang.equals(n.getSourceLanguage()) ? n.getContent()
                    : llm.translate(n.getContent(), targetLang);
            byte[] audio = speech.synthesize(text, targetLang);
            translated.put(targetLang, text);
            tts.put(targetLang, audio);
        }
        for (Device d : targets) {
            String targetLang = lang(d.getStaffLanguage());
            NotificationDelivery delivery = NotificationDelivery.builder()
                    .notificationId(n.getId()).deviceId(d.getDeviceId())
                    .translatedText(translated.get(targetLang)).targetLanguage(targetLang).build();
            if (pusher.sendToDevice(d.getDeviceId(), message(n.getId(), translated.get(targetLang), targetLang),
                    tts.get(targetLang), "bc" + n.getId())) {
                delivery.setDeliveredAt(Instant.now());
            }
            deliveries.save(delivery);
        }
        n.setStatus(OperationNotification.STATUS_SENT);
        notifications.save(n);
    }

    private List<Device> resolveTargets(OperationNotification n) {
        if (OperationNotification.TARGET_GROUP.equals(n.getTargetType())) {
            long groupId = Long.parseLong(n.getTargetId());
            return members.findByGroupId(groupId).stream()
                    .map(m -> devices.findByDeviceId(m.getDeviceId()).orElse(null))
                    .filter(Objects::nonNull).toList();
        }
        return devices.findByDeviceId(n.getTargetId()).stream().toList();
    }

    private OutboundMessage message(Long notificationId, String text, String targetLanguage) {
        return OutboundMessage.builder().type("broadcast").broadcastId(notificationId)
                .text(text).targetLanguage(targetLanguage).build();
    }

    private String lang(String staffLanguage) {
        return staffLanguage != null && !staffLanguage.isBlank() ? staffLanguage : DEFAULT_LANG;
    }
}
