package com.aicard.meeting;

import com.aicard.common.domain.Device;
import com.aicard.gateway.auth.DeviceAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MeetingDeviceControllerTest {

    private DeviceAuthenticator authenticator;
    private MeetingService meetings;
    private MeetingConfig config;
    private MeetingDeviceController controller;

    @BeforeEach
    void setUp() {
        authenticator = mock(DeviceAuthenticator.class);
        meetings = mock(MeetingService.class);
        config = mock(MeetingConfig.class);
        when(config.isEnabled()).thenReturn(true);
        when(config.getMaxChunkBytes()).thenReturn(2_097_152);
        when(config.getRetentionDays()).thenReturn(30);
        when(config.getFinalizeAfterMinutes()).thenReturn(10);
        controller = new MeetingDeviceController(authenticator, meetings, config);
        when(authenticator.authenticate("dev-1", "token")).thenReturn(
                Device.builder().deviceId("dev-1").customerId(7L).storeId(8L).build());
    }

    @Test
    void startRejectsMissingChunkSec() {
        MeetingDeviceController.StartRequest req = req(null);
        assertError(() -> controller.start("dev-1", "Bearer token", req), "E_MEETING_INVALID_ARGUMENT");
        verifyNoInteractions(meetings);
    }

    @Test
    void startRejectsChunkSecOutsideProtocolRange() {
        MeetingDeviceController.StartRequest req = req(31);
        assertError(() -> controller.start("dev-1", "Bearer token", req), "E_MEETING_INVALID_ARGUMENT");
        verifyNoInteractions(meetings);
    }

    @Test
    void startRejectsMissingMeetingFeature() {
        MeetingDeviceController.StartRequest req = new MeetingDeviceController.StartRequest(
                "key-1", null, "pcm16", 16000, 30, "定例会議",
                "1.0.0", List.of("other_feature"), "long_press", true, "v1");
        assertError(() -> controller.start("dev-1", "Bearer token", req), "E_MEETING_UNSUPPORTED_FW");
        verifyNoInteractions(meetings);
    }

    @Test
    void startRejectsNonLongPressTrigger() {
        MeetingDeviceController.StartRequest req = new MeetingDeviceController.StartRequest(
                "key-1", null, "pcm16", 16000, 30, "定例会議",
                "1.0.0", List.of("meeting_recording"), "remote", true, "v1");
        assertError(() -> controller.start("dev-1", "Bearer token", req), "E_MEETING_INVALID_ARGUMENT");
        verifyNoInteractions(meetings);
    }

    @Test
    void startRejectsNoticeNotPlayed() {
        MeetingDeviceController.StartRequest req = new MeetingDeviceController.StartRequest(
                "key-1", null, "pcm16", 16000, 30, "定例会議",
                "1.0.0", List.of("meeting_recording"), "long_press", false, "v1");
        assertError(() -> controller.start("dev-1", "Bearer token", req), "E_MEETING_INVALID_ARGUMENT");
        verifyNoInteractions(meetings);
    }

    @Test
    void startRejectsInvalidBearer() {
        assertError(() -> controller.start("dev-1", "token", req(30)), "E_MEETING_AUTH");
        verifyNoInteractions(meetings);
    }

    @Test
    void startReturnsProtocolFields() {
        Instant expires = Instant.now().plusSeconds(3600);
        Meeting m = Meeting.builder().meetingId("mtg-1").status(Meeting.STATUS_RECORDING)
                .chunkSec(30).audioExpiresAt(expires).build();
        when(meetings.start(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(m);

        MeetingDeviceController.StartResponse res =
                controller.start("dev-1", "Bearer token", req(30));

        assertThat(res.meetingId()).isEqualTo("mtg-1");
        assertThat(res.status()).isEqualTo(Meeting.STATUS_RECORDING);
        assertThat(res.chunkSec()).isEqualTo(30);
        assertThat(res.maxChunkBytes()).isEqualTo(2_097_152);
        assertThat(res.retentionDays()).isEqualTo(30);
        assertThat(res.chunkUrl()).isEqualTo("/api/v1/meetings/mtg-1/chunks/{seq}");
        assertThat(res.audioExpiresAt()).isEqualTo(expires);
    }

    @Test
    void statusReturnsMissingSeqsAndUploadedCount() {
        Meeting m = Meeting.builder().meetingId("mtg-1").status(Meeting.STATUS_UPLOADING)
                .lastSeq(2).expectedLastSeq(3).missingSeqs("[1,3]")
                .audioExpiresAt(Instant.now().plusSeconds(60)).build();
        when(meetings.statusForDevice(any(), any())).thenReturn(m);
        when(meetings.missingList(m)).thenReturn(List.of(1, 3));
        when(meetings.uploadedCount("mtg-1")).thenReturn(2L);

        MeetingDeviceController.StatusResponse res =
                controller.status("dev-1", "Bearer token", "mtg-1");

        assertThat(res.status()).isEqualTo(Meeting.STATUS_UPLOADING);
        assertThat(res.lastSeq()).isEqualTo(2);
        assertThat(res.expectedLastSeq()).isEqualTo(3);
        assertThat(res.missingSeqs()).containsExactly(1, 3);
        assertThat(res.uploadedCount()).isEqualTo(2L);
    }

    private static MeetingDeviceController.StartRequest req(Integer chunkSec) {
        return new MeetingDeviceController.StartRequest("key-1", null, "pcm16", 16000, chunkSec,
                "定例会議", "1.0.0", List.of("meeting_recording"), "long_press", true, "v1");
    }

    private static void assertError(Runnable call, String errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(MeetingException.class)
                .extracting(e -> ((MeetingException) e).getErrorCode())
                .isEqualTo(errorCode);
    }
}
