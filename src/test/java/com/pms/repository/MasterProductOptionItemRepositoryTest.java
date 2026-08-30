package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link MasterProductOptionItemRepository#deleteByOptionId} against a real DB (FEATURE_2608_06 / 96 ⑦).
 *
 * <p>The "replace an option's items" contract is delete + re-insert in ONE transaction. With the derived
 * (entity-loading) delete, Hibernate's ActionQueue flushed the INSERTs before the DELETEs, so re-inserting the
 * same {@code (option, product)} pair tripped {@code uq_mpoi_option_product} — i.e. every option edit that kept
 * its components 500'd. Only a real DB round-trip can catch that: a mocked service test never flushes, and a
 * {@code @DataJpaTest} slice cannot call {@code MasterProductServiceImpl.updateOption} (no service beans).</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class MasterProductOptionItemRepositoryTest {

    @Autowired private MasterProductOptionItemRepository repository;
    @Autowired private TestEntityManager em;

    @Test
    void deleteByOptionId_thenReinsertSamePair_doesNotViolateUniqueConstraint() {
        // Given an option whose item vector already covers one product
        // (do NOT set tenantId: @TenantId stamps it — NO_TENANT in this slice, self-consistent).
        Product product = em.persist(Product.builder().productName("생수").active(true).build());
        MasterProduct master = em.persist(MasterProduct.builder().name("마스터").active(true).build());
        MasterProductOption option = em.persist(
                MasterProductOption.builder().masterProduct(master).name("1세트").build());
        em.persist(MasterProductOptionItem.builder().option(option).product(product).quantity(2).build());
        em.flush();

        // When the service's replace sequence runs inside one transaction: delete, then re-insert the SAME pair
        assertThatCode(() -> {
            repository.deleteByOptionId(option.getId());
            repository.save(MasterProductOptionItem.builder()
                    .option(option).product(product).quantity(5).build());
            em.flush();
        }).doesNotThrowAnyException();

        // Then the old row is gone and exactly one item (the new quantity) remains
        em.clear();
        assertThat(repository.findByOptionId(option.getId()))
                .singleElement()
                .extracting(MasterProductOptionItem::getQuantity).isEqualTo(5);
    }
}
