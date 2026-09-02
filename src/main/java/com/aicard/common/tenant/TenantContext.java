package com.aicard.common.tenant;

public final class TenantContext {
    private static final ThreadLocal<Tenant> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Tenant tenant) { HOLDER.set(tenant); }
    public static Tenant get() { return HOLDER.get(); }
    public static Tenant require() {
        Tenant t = HOLDER.get();
        if (t == null) {
            throw new IllegalStateException("tenant context not set");
        }
        return t;
    }
    public static void clear() { HOLDER.remove(); }
}
