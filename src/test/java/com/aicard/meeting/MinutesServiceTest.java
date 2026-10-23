package com.aicard.meeting;

import com.aicard.docgen.DocxRenderer;
import com.aicard.skb.provider.mock.MockLlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MinutesServiceTest {

    private final MockLlmProvider llm = new MockLlmProvider();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MinutesService service = new MinutesService(llm, new DocxRenderer(), mapper);

    @Test
    void generatesStructuredMinutesWithActionsAndEvidence() throws Exception {
        Meeting meeting = meeting();
        MeetingTranscript transcript = MeetingTranscriptionService.demoTranscript();

        MinutesService.Result result = service.generate(meeting, transcript);

        Map<String, Object> json = mapper.readValue(result.json(), Map.class);
        assertThat(json.get("title")).isEqualTo("定例会議");
        assertThat(json.get("next_meeting")).isEqualTo("11月7日10時 本会議室");
        assertThat((Iterable<?>) json.get("decisions")).hasSize(2);
        assertThat(result.actions()).hasSize(3);
        assertThat(result.actions().get(2).dueDate()).isEqualTo("要確認"); // 不编造期限
        assertThat(result.actions().get(0).sourceRef()).isEqualTo("01:20");

        byte[] docx = service.renderDocx(meeting,
                mapper.readValue(result.json(), Map.class), java.util.List.of());
        assertThat(docx.length).isGreaterThan(1000);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            String text = doc.getParagraphs().stream()
                    .map(p -> p.getText() == null ? "" : p.getText())
                    .reduce("", (a, b) -> a + b + "\n");
            assertThat(text).contains("【決定事項】").contains("経費精算").contains("AI が作成した下書き");
        }
    }

    @Test
    void unparseableLlmOutputFallsBackWithoutFabrication() throws Exception {
        llm.setMinutes("これは JSON ではありません");
        MinutesService.Result result = service.generate(meeting(), MeetingTranscriptionService.demoTranscript());

        Map<String, Object> json = mapper.readValue(result.json(), Map.class);
        assertThat(json.get("location")).isEqualTo("要確認");
        assertThat(json.get("next_meeting")).isEqualTo("要確認");
        assertThat(result.actions()).isEmpty();
        assertThat((Iterable<?>) json.get("discussion")).isNotEmpty();
    }

    private static Meeting meeting() {
        return Meeting.builder()
                .meetingId("mtg-1").customerId(1L).storeId(1L).deviceId("dev-1")
                .status(Meeting.STATUS_DRAFTING).startedAt(Instant.parse("2026-10-23T01:00:00Z"))
                .chunkSec(30).codec("pcm16").sampleRate(16000).build();
    }
}
