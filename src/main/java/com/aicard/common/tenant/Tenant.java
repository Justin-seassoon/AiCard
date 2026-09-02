package com.aicard.common.tenant;

public record Tenant(Long customerId, Long storeId) {
    public static Tenant of(Long customerId, Long storeId) {
        return new Tenant(customerId, storeId);
    }
}
