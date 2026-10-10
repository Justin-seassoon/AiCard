package com.aicard.notification.service;

import com.aicard.broadcast.domain.DeviceGroupMember;
import com.aicard.broadcast.repository.DeviceGroupMemberRepository;
import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.gateway.push.DevicePusher;
import com.aicard.notification.domain.OperationNotification;
import com.aicard.notification.repository.NotificationDeliveryRepository;
import com.aicard.notification.repository.OperationNotificationRepository;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.skb.provider.LLMProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private final OperationNotificationRepository notifications = mock(OperationNotificationRepository.class);
    private final NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceGroupMemberRepository members = mock(DeviceGroupMemberRepository.class);
    private final LLMProvider llm = mock(LLMProvider.class);
    private final SpeechProvider speech = mock(SpeechProvider.class);
    private final DevicePusher pusher = mock(DevicePusher.class);
    private final NotificationService service = new NotificationService(
            notifications, deliveries, devices, members, llm, speech, pusher);

    @Test
    void dispatchDueSendsToDeviceAndMarksSent() {
        OperationNotification n = notification("device", "dev-a");
        when(notifications.findByCustomerIdAndStoreIdAndStatusAndScheduleAtLessThanEqual(
                any(), any(), any(), any())).thenReturn(List.of(n));
        when(devices.findByDeviceId("dev-a")).thenReturn(Optional.of(device("dev-a", "ja-JP")));
        when(speech.synthesize(anyString(), anyString())).thenReturn(new byte[]{1});
        when(pusher.sendToDevice(anyString(), any(), any(), anyString())).thenReturn(true);
        when(deliveries.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(notifications.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.dispatchDue(10L, 20L);

        assertThat(count).isEqualTo(1);
        verify(deliveries, times(1)).save(any());
        assertThat(n.getStatus()).isEqualTo(OperationNotification.STATUS_SENT);
    }

    @Test
    void dispatchDueResolvesGroupTargets() {
        OperationNotification n = notification("group", "5");
        when(notifications.findByCustomerIdAndStoreIdAndStatusAndScheduleAtLessThanEqual(
                any(), any(), any(), any())).thenReturn(List.of(n));
        when(members.findByGroupId(5L)).thenReturn(List.of(
                member("dev-a"), member("dev-b")));
        when(devices.findByDeviceId("dev-a")).thenReturn(Optional.of(device("dev-a", "ja-JP")));
        when(devices.findByDeviceId("dev-b")).thenReturn(Optional.of(device("dev-b", "ja-JP")));
        when(speech.synthesize(anyString(), anyString())).thenReturn(new byte[]{1});
        when(pusher.sendToDevice(anyString(), any(), any(), anyString())).thenReturn(true);
        when(deliveries.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(notifications.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.dispatchDue(10L, 20L);

        verify(deliveries, times(2)).save(any());
    }

    private OperationNotification notification(String targetType, String targetId) {
        return OperationNotification.builder()
                .id(1L).customerId(10L).storeId(20L)
                .content("检查通知").sourceLanguage("ja-JP")
                .targetType(targetType).targetId(targetId)
                .triggerType("once").status(OperationNotification.STATUS_SCHEDULED).build();
    }

    private DeviceGroupMember member(String deviceId) {
        return DeviceGroupMember.builder().id(1L).groupId(5L).deviceId(deviceId).build();
    }

    private Device device(String deviceId, String staffLanguage) {
        return Device.builder().id(1L).deviceId(deviceId)
                .customerId(10L).storeId(20L).token("t").staffLanguage(staffLanguage).build();
    }
}
