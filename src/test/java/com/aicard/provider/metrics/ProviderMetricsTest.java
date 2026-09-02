package com.aicard.provider.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderMetricsTest {

    @Test
    void countsSuccessAndFailurePerProviderAndCapability() {
        ProviderMetrics m = new ProviderMetrics();
        m.recordSuccess("azure", "mt");
        m.recordSuccess("azure", "mt");
        m.recordFailure("azure", "asr");
        m.recordSuccess("google", "mt");

        assertThat(m.successCount("azure", "mt")).isEqualTo(2);
        assertThat(m.successCount("google", "mt")).isEqualTo(1);
        assertThat(m.failureCount("azure", "asr")).isEqualTo(1);
        assertThat(m.successCount("azure", "asr")).isZero();
    }
}
