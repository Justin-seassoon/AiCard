package com.aicard.broadcast.service;

import com.aicard.broadcast.domain.Broadcast;
import com.aicard.broadcast.domain.DeviceGroup;
import com.aicard.broadcast.domain.DeviceGroupMember;
import com.aicard.broadcast.repository.BroadcastDeliveryRepository;
import com.aicard.broadcast.repository.BroadcastRepository;
import com.aicard.broadcast.repository.DeviceGroupMemberRepository;
import com.aicard.broadcast.repository.DeviceGroupRepository;
import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.gateway.state.DeviceConnectionRegistry;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.gateway.protocol.OutboundMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BroadcastServiceTest {

    private final DeviceGroupRepository groups = mock(DeviceGroupRepository.class);
    private final DeviceGroupMemberRepository members = mock(DeviceGroupMemberRepository.class);
    private final BroadcastRepository broadcasts = mock(BroadcastRepository.class);
    private final BroadcastDeliveryRepository deliveries = mock(BroadcastDeliveryRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final LLMProvider llm = mock(LLMProvider.class);
    private final SpeechProvider speech = mock(SpeechProvider.class);
    private final DeviceConnectionRegistry connections = mock(DeviceConnectionRegistry.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final BroadcastService service = new BroadcastService(
            groups, members, broadcasts, deliveries, devices, llm, speech, connections, mapper);

    @Test
    void translatesAndSynthesizesPerLanguageNotPerMember() {
        DeviceGroup group = DeviceGroup.builder().id(1L).code("site1").name("工地1")
                .customerId(10L).storeId(20L).build();
        when(groups.findByIdAndCustomerIdAndStoreId(1L, 10L, 20L)).thenReturn(Optional.of(group));
        when(members.findByGroupId(1L)).thenReturn(List.of(
                member(1L, "dev-a"), member(2L, "dev-b"), member(3L, "dev-c")));
        when(devices.findByDeviceId("dev-a")).thenReturn(Optional.of(device("dev-a", "zh-CN")));
        when(devices.findByDeviceId("dev-b")).thenReturn(Optional.of(device("dev-b", "zh-CN")));
        when(devices.findByDeviceId("dev-c")).thenReturn(Optional.of(device("dev-c", "vi-VN")));
        when(llm.translate(anyString(), anyString())).thenReturn("译文");
        when(speech.synthesize(anyString(), anyString())).thenReturn(new byte[]{1, 2, 3});
        when(broadcasts.save(any())).thenAnswer(inv -> {
            Broadcast b = inv.getArgument(0);
            b.setId(100L);
            return b;
        });

        Broadcast result = service.broadcast(1L, "安全须知", "ja-JP", 10L, 20L);

        assertThat(result.getId()).isEqualTo(100L);
        // 2 种语言（zh-CN + vi-VN），各翻译 / TTS 一次，而非按 3 个成员各一次
        verify(llm, times(2)).translate(anyString(), anyString());
        verify(speech, times(2)).synthesize(anyString(), anyString());
        // 3 个成员各落一条投递记录
        verify(deliveries, times(3)).save(any());
    }

    @Test
    void skipsTranslationWhenTargetEqualsSource() {
        DeviceGroup group = DeviceGroup.builder().id(1L).code("site1").name("工地1")
                .customerId(10L).storeId(20L).build();
        when(groups.findByIdAndCustomerIdAndStoreId(1L, 10L, 20L)).thenReturn(Optional.of(group));
        when(members.findByGroupId(1L)).thenReturn(List.of(member(1L, "dev-a")));
        when(devices.findByDeviceId("dev-a")).thenReturn(Optional.of(device("dev-a", "ja-JP")));
        when(speech.synthesize(anyString(), anyString())).thenReturn(new byte[]{1});
        when(broadcasts.save(any())).thenAnswer(inv -> {
            Broadcast b = inv.getArgument(0);
            b.setId(100L);
            return b;
        });

        service.broadcast(1L, "安全须知", "ja-JP", 10L, 20L);

        // 目标语言 == 源语言，不调用翻译
        verify(llm, times(0)).translate(anyString(), anyString());
        verify(speech, times(1)).synthesize(anyString(), anyString());
    }

    @Test
    void broadcastTtsEndCarriesTurnId() throws Exception {
        DeviceGroup group = DeviceGroup.builder().id(1L).code("site1").name("工地1")
                .customerId(10L).storeId(20L).build();
        when(groups.findByIdAndCustomerIdAndStoreId(1L, 10L, 20L)).thenReturn(Optional.of(group));
        when(members.findByGroupId(1L)).thenReturn(List.of(member(1L, "dev-a")));
        when(devices.findByDeviceId("dev-a")).thenReturn(Optional.of(device("dev-a", "ja-JP")));
        when(speech.synthesize(anyString(), anyString())).thenReturn(new byte[9000]); // >8KB，分 2 片
        when(broadcasts.save(any())).thenAnswer(inv -> {
            Broadcast b = inv.getArgument(0);
            b.setId(100L);
            return b;
        });

        WebSocketSession session = mock(WebSocketSession.class);
        when(connections.findOnline("dev-a")).thenReturn(Optional.of(session));

        ArgumentCaptor<TextMessage> textCaptor = ArgumentCaptor.forClass(TextMessage.class);

        service.broadcast(1L, "安全须知", "ja-JP", 10L, 20L);

        verify(session, atLeast(2)).sendMessage(textCaptor.capture());
        OutboundMessage ttsEnd = null;
        for (TextMessage m : textCaptor.getAllValues()) {
            try {
                OutboundMessage out = mapper.readValue(m.getPayload(), OutboundMessage.class);
                if ("tts_end".equals(out.type())) {
                    ttsEnd = out;
                }
            } catch (Exception ignored) {
            }
        }
        assertThat(ttsEnd).isNotNull();
        assertThat(ttsEnd.turnId()).isEqualTo("bc100"); // 广播 tts_end 带 turnId，端侧据此关联
    }

    private DeviceGroupMember member(Long id, String deviceId) {
        return DeviceGroupMember.builder().id(id).groupId(1L).deviceId(deviceId).build();
    }

    private Device device(String deviceId, String staffLanguage) {
        return Device.builder().id(1L).deviceId(deviceId)
                .customerId(10L).storeId(20L).token("t").staffLanguage(staffLanguage).build();
    }
}
