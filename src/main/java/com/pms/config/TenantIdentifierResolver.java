package com.pms.config;

import com.pms.security.TenantContext;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Single choke point that feeds the current tenant to Hibernate's {@code @TenantId} pipeline.
 *
 * <p>Reads {@link TenantContext} (populated per-request by the JWT filter) and hands the value
 * to Hibernate, which then auto-filters SELECTs ({@code tenant_id = ?}) and auto-sets INSERTs
 * on {@code @TenantId} entities. Registered via {@link HibernatePropertiesCustomizer}.</p>
 *
 * <p>⚠️ {@link #NO_TENANT} (-1) is returned when there is no context (public endpoints, boot,
 * non-web threads). Hibernate requires a non-null identifier; -1 matches no real tenant, so
 * SELECTs return 0 rows and INSERTs would fail the tenant FK (safe-by-default). The FK safety
 * relies on changeset 002 having added the {@code tenant} FK on {@code tenant_id}.</p>
 */
@Component
public class TenantIdentifierResolver
        implements CurrentTenantIdentifierResolver<Long>, HibernatePropertiesCustomizer {

    // Hibernate requires a non-null identifier. Sentinel that matches no real tenant.
    private static final Long NO_TENANT = -1L;

    @Override
    public Long resolveCurrentTenantIdentifier() {
        Long tenantId = TenantContext.get();
        return tenantId != null ? tenantId : NO_TENANT;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
