package com.aicard.ingestion.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IngestionServiceTest {

    private final IngestionService service = new IngestionService(mock(JdbcTemplate.class));

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
}
