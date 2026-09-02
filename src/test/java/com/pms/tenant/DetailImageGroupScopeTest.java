package com.pms.tenant;

import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.MasterProductRepository;
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
 * Proves the tenant scoping of {@code MasterImageZoneAssignmentRepository.deleteByZoneIdScoped}
 * (FEATURE_2609_03) — the group-delete cleanup.
 *
 * <p>🔴 {@link MasterImageZoneAssignment} carries no {@code @TenantId}: isolation exists ONLY because the
 * bulk delete routes through a {@code MasterProduct} subquery. The service unit tests are Mockito-based
 * and cannot see the query at all, so this is the only guard against someone replacing it with a derived
 * {@code deleteByZoneId(String)} that would wipe every tenant's mappings.</p>
 *
 * <p>⚠️ Intentionally NOT {@code @Transactional}: Hibernate resolves the tenant once per session, so a
 * single shared transaction cannot seed one tenant's rows and delete as another (mirrors
 * {@code MasterProductTenantIsolationTest}). Cleanup uses native SQL to bypass the tenant filter.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisplayName("Detail image group mapping cleanup is tenant-scoped")
class DetailImageGroupScopeTest {

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;
    private static final String ZONE = "product_photos";

    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductImageRepository masterProductImageRepository;
    @Autowired private MasterImageZoneAssignmentRepository assignmentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbcTemplate.execute("delete from master_image_zone_assignment");
        jdbcTemplate.execute("delete from master_product_image");
        jdbcTemplate.execute("delete from master_product");
    }

    @Test
    @DisplayName("deleting one tenant's zone mappings leaves the other tenant's rows intact")
    void deleteByZoneIdScoped_doesNotTouchOtherTenants() {
        Long tenant1AssignmentId = seedMapping(TENANT_1, "테넌트1 마스터");
        Long tenant2AssignmentId = seedMapping(TENANT_2, "테넌트2 마스터");

        TenantContext.set(TENANT_1);
        int deleted = assignmentRepository.deleteByZoneIdScoped(ZONE);
        TenantContext.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(existsById(tenant1AssignmentId)).isFalse();
        assertThat(existsById(tenant2AssignmentId)).isTrue();
    }

    /** Seeds master → pool image → zone mapping for one tenant, each write in its own session. */
    private Long seedMapping(Long tenantId, String masterName) {
        TenantContext.set(tenantId);
        try {
            MasterProduct master = masterProductRepository.save(
                    MasterProduct.builder().name(masterName).active(true).build());
            MasterProductImage image = masterProductImageRepository.save(MasterProductImage.builder()
                    .masterProduct(master).sortOrder(0).imageUrl("https://example.com/a.jpg").build());
            return assignmentRepository.save(MasterImageZoneAssignment.builder()
                    .image(image).zoneId(ZONE).sortOrder(0).build()).getId();
        } finally {
            TenantContext.clear();
        }
    }

    /** Native lookup: the entity has no {@code @TenantId}, but going through JPA would need a session. */
    private boolean existsById(Long assignmentId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from master_image_zone_assignment where id = ?",
                Integer.class, assignmentId);
        return count != null && count > 0;
    }
}
