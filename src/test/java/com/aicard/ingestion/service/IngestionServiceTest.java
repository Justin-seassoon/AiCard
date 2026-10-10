package com.aicard.ingestion.service;

import com.aicard.ingestion.model.TranslationSample;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IngestionServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IngestionService service = new IngestionService(jdbc);

    @Test
    void sanitizesPersonalData() {
        String sanitized = service.sanitize("电话 09012345678，邮箱 a@b.com");

        assertThat(sanitized).doesNotContain("09012345678");
        assertThat(sanitized).doesNotContain("a@b.com");
        assertThat(sanitized).contains("[EMAIL]");
        assertThat(sanitized).contains("####");
    }

    @Test
    void sanitizeNullReturnsNull() {
        assertThat(service.sanitize(null)).isNull();
    }

    @Test
    void recordIncludesDeviceId() {
        TranslationSample sample = new TranslationSample(10L, 20L, "dev-a", "s1", "t1",
                "zh", "ja", "你好", "こんにちは", null, "counterparty");

        service.record(sample);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        // 参数顺序：customer_id, store_id, device_id, session_id, turn_id, ...
        assertThat(args.getValue()[2]).isEqualTo("dev-a");
    }
}
