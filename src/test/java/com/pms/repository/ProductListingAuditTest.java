package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auditing on {@link ProductListing} (FEATURE_2608_06 / 104 Step 1).
 *
 * <p>The entity is saved exclusively through {@code save(x.toBuilder()...build())}, and Lombok's
 * {@code toBuilder()} does NOT copy the inherited {@code BaseEntity} fields — so the interesting question is
 * whether an update wipes {@code created_date}. It cannot: the column is {@code updatable=false}, so it is
 * left out of the UPDATE statement. Only a real DB round-trip shows that; a mocked service test never flushes.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class ProductListingAuditTest {

    @Autowired private ProductListingRepository repository;
    @Autowired private TestEntityManager em;

    @Test
    void save_stampsCreatedAt_andToBuilderUpdateKeepsItWhileRefreshingUpdatedAt() {
        // Given a persisted listing (do NOT set tenantId: @TenantId stamps it — NO_TENANT in this slice)
        Seller seller = em.persist(Seller.builder()
                .sellerName("판매자").businessRegistration("123-45-67890").build());
        ProductListing saved = repository.save(ProductListing.builder()
                .platform("COUPANG").name("리스팅").seller(seller).build());
        em.flush();

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        LocalDateTime createdAt = saved.getCreatedAt();

        // When the row is re-saved through the toBuilder pattern the whole codebase uses
        em.clear();
        ProductListing reloaded = repository.findById(saved.getId()).orElseThrow();
        repository.save(reloaded.toBuilder().name("리스팅-수정").build());
        em.flush();

        // Then created_date survives (updatable=false) and modified_date is re-stamped
        em.clear();
        ProductListing updated = repository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("리스팅-수정");
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isNotNull();
    }
}
