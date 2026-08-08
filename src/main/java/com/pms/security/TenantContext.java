package com.pms.security;

/**
 * Holds the current request's tenant identifier (ThreadLocal).
 *
 * <p><b>Populated by</b>: {@link JwtAuthenticationFilter} (set per request, cleared in finally).
 * <b>Read by</b>: {@code com.pms.config.TenantIdentifierResolver} (Hibernate choke point).</p>
 *
 * <p>⚠️ Must always be cleared at request end — thread-pool reuse means a leaked value
 * would leak across tenants (cross-tenant data exposure).</p>
 *
 * <p>❌ Do not call {@link #set(Long)} from controllers/services — for web requests the
 * filter is the single entry point. Only non-web batches (e.g. scheduled sync, phase 03)
 * may set it explicitly, and must clear it afterwards.</p>
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    public static Long get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
