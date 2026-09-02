package com.aicard.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void requireThrowsWhenNotSet() {
        assertThatThrownBy(TenantContext::require)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setAndGetRoundTrip() {
        TenantContext.set(Tenant.of(1L, 2L));
        assertThat(TenantContext.require()).isEqualTo(Tenant.of(1L, 2L));
    }

    @Test
    void clearRemovesContext() {
        TenantContext.set(Tenant.of(1L, 2L));
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
    }
}
