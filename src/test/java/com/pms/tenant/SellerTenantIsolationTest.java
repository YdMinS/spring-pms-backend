package com.pms.tenant;

import com.pms.domain.Seller;
import com.pms.repository.SellerRepository;
import com.pms.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves Hibernate {@code @TenantId} isolation + tenant-scoped unique constraint for {@link Seller}.
 *
 * <p>Representative for the remaining tenant-owned entities (slice 03): they all share the same
 * {@code @TenantId} discriminator mechanism, so one SELECT-isolation proof + one unique-constraint
 * proof covers the pattern (per-entity duplication is CUT).</p>
 *
 * <p>⚠️ Not {@code @Transactional}: Hibernate resolves the tenant once per session-open, so each
 * repository call must run in its own session to read the live {@link TenantContext}. Cleanup uses
 * native SQL to bypass the tenant filter (deletes across all tenants). See {@link ProductTenantIsolationTest}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisplayName("Seller tenant isolation (@TenantId) + tenant-scoped unique constraint")
class SellerTenantIsolationTest {

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;
    private static final String BIZ = "100-11-11111";

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbcTemplate.execute("delete from seller");   // native → removes every tenant's rows
    }

    @Test
    @DisplayName("SELECT is auto-filtered by tenant")
    void selectIsScopedToCurrentTenant() {
        TenantContext.set(TENANT_1);
        sellerRepository.save(sellerFixture("100-11-11111", "t1"));
        TenantContext.set(TENANT_2);
        sellerRepository.save(sellerFixture("200-22-22222", "t2"));

        TenantContext.set(TENANT_1);
        assertThat(sellerRepository.findAll())
                .extracting(Seller::getSellerName)
                .containsExactly("t1");   // t2 not visible = isolation proven

        TenantContext.set(TENANT_2);
        assertThat(sellerRepository.findAll())
                .extracting(Seller::getSellerName)
                .containsExactly("t2");
    }

    @Test
    @DisplayName("business_registration unique is tenant-scoped: same value allowed across tenants, rejected within one")
    void uniqueConstraintIsTenantScoped() {
        TenantContext.set(TENANT_1);
        sellerRepository.saveAndFlush(sellerFixture(BIZ, "t1"));

        // Same business_registration under a different tenant → allowed (scoped to (tenant_id, biz)).
        TenantContext.set(TENANT_2);
        assertThatCode(() -> sellerRepository.saveAndFlush(sellerFixture(BIZ, "t2")))
                .doesNotThrowAnyException();

        // Duplicate within the same tenant → still rejected (constraint preserved).
        TenantContext.set(TENANT_1);
        assertThatThrownBy(() -> sellerRepository.saveAndFlush(sellerFixture(BIZ, "t1-dup")));
    }

    private Seller sellerFixture(String businessRegistration, String sellerName) {
        // tenantId is intentionally omitted — Hibernate @TenantId sets it from TenantContext.
        return Seller.builder()
                .sellerName(sellerName)
                .businessRegistration(businessRegistration)
                .build();
    }
}
