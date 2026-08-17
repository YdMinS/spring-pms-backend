package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductCategory;
import com.pms.domain.Package;
import com.pms.domain.ProductListing;
import com.pms.service.MasterChannelConfigServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolver + mapping regression against a real DB (FEATURE_2608_06 / 13): resolving a cell's category
 * (master × platform, lazy master.category link) and its delivery (lazy {@code master.defaultDelivery}) both
 * work through the persistence chain — mirroring the {@code account.getSeller()} lazy-init regression: the
 * config graph must be reachable when the resolver runs inside the caller's {@code @Transactional} boundary.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class MasterChannelConfigResolutionTest {

    @Autowired private MasterProductCategoryRepository masterProductCategoryRepository;
    @Autowired private TestEntityManager em;

    private MasterChannelConfigServiceImpl service() {
        return new MasterChannelConfigServiceImpl(masterProductCategoryRepository);
    }

    private Long persistMasterWithConfig() {
        Carrier carrier = em.persist(Carrier.builder().name("CJ").isActive(true).build());
        CarrierRate delivery = em.persist(CarrierRate.builder()
                .carrier(carrier).type("STANDARD").cost(new BigDecimal("2500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());
        Package box = em.persist(Package.builder()
                .type("M").cost(new BigDecimal("500")).effectiveDate(LocalDate.now()).isDefault(false).build());
        Category category = em.persist(Category.builder()
                .name("신발").platform("COUPANG").platformCategoryId("cat-1").build());
        MasterProduct master = em.persist(MasterProduct.builder()
                .name("마스터").active(true).defaultDelivery(delivery).defaultPackage(box).build());
        em.persist(MasterProductCategory.builder()
                .masterProduct(master).platform("COUPANG").category(category).build());
        em.flush();
        em.clear();
        return master.getId();
    }

    @Test
    void resolvesCategoryAndDeliveryThroughThePersistenceChain() {
        Long masterId = persistMasterWithConfig();

        // Reload the master (managed) and wrap it in a transient cell, as the real callers hold it.
        MasterProduct master = em.find(MasterProduct.class, masterId);
        ProductListing cell = ProductListing.builder().platform("COUPANG").masterProduct(master).build();

        // category = master × platform; delivery = master default (no option override) — no LazyInit exception.
        assertThat(service().resolveCategory(cell).getPlatformCategoryId()).isEqualTo("cat-1");
        assertThat(service().resolveDelivery(cell, null).getCost()).isEqualByComparingTo("2500");
        assertThat(service().resolvePackage(cell, null).getCost()).isEqualByComparingTo("500");
    }
}
