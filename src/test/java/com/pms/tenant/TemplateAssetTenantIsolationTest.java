package com.pms.tenant;

import com.pms.domain.TemplateAsset;
import com.pms.repository.TemplateAssetRepository;
import com.pms.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Hibernate {@code @TenantId} isolation for {@link TemplateAsset}: a tenant's list never contains
 * another tenant's asset. NOT {@code @Transactional} — Hibernate resolves the tenant at session-open, so
 * each repo call must open its own session against the live {@link TenantContext}. Cleanup uses native SQL
 * to bypass the tenant filter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisplayName("Template asset tenant isolation (@TenantId)")
class TemplateAssetTenantIsolationTest {

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;

    @Autowired private TemplateAssetRepository templateAssetRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbcTemplate.execute("delete from thumbnail_asset");
    }

    @Test
    @DisplayName("list is auto-scoped to the current tenant")
    void listScopedToCurrentTenant() {
        TenantContext.set(TENANT_1);
        templateAssetRepository.save(asset("A"));
        TenantContext.set(TENANT_2);
        templateAssetRepository.save(asset("B"));

        TenantContext.set(TENANT_1);
        assertThat(templateAssetRepository.findAllByOrderByIdDesc())
                .extracting(TemplateAsset::getName)
                .containsExactly("A");   // B not visible = isolation proven

        TenantContext.set(TENANT_2);
        assertThat(templateAssetRepository.findAllByOrderByIdDesc())
                .extracting(TemplateAsset::getName)
                .containsExactly("B");
    }

    private TemplateAsset asset(String name) {
        // tenantId auto-set by @TenantId from the live TenantContext.
        return TemplateAsset.builder().name(name).storageKey("thumbnail-assets/" + name + ".png").build();
    }
}
