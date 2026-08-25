package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.MasterProduct;
import com.pms.domain.Package;
import com.pms.domain.PlatformCategory;
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
 * Resolver + mapping regression against a real DB (FEATURE_2608_06 / 44): resolving a cell's standard
 * category (lazy master.category link), its platform code (CategoryMapping) and its delivery (lazy
 * {@code master.defaultDelivery}) all work through the persistence chain — mirroring the
 * {@code account.getSeller()} lazy-init regression: the config graph must be reachable when the resolver runs
 * inside the caller's {@code @Transactional} boundary.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class MasterChannelConfigResolutionTest {

    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private TestEntityManager em;

    private MasterChannelConfigServiceImpl service() {
        return new MasterChannelConfigServiceImpl(categoryMappingRepository);
    }

    private Long persistMasterWithConfig() {
        Carrier carrier = em.persist(Carrier.builder().name("CJ").isActive(true).build());
        CarrierRate delivery = em.persist(CarrierRate.builder()
                .carrier(carrier).type("STANDARD").cost(new BigDecimal("2500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());
        Package box = em.persist(Package.builder()
                .type("M").cost(new BigDecimal("500")).effectiveDate(LocalDate.now()).isDefault(false).build());
        Category category = em.persist(Category.builder().name("신발").build());
        // 52: the platform code comes from the mapping's linked PlatformCategory FK (tenant-scoped node that
        // owns the mall code + commission), not the deprecated string column.
        PlatformCategory platformCategory = em.persist(PlatformCategory.builder()
                .platform("COUPANG").code("cat-1").name("운동화")
                .commissionRate(new BigDecimal("0.10")).build());
        em.persist(CategoryMapping.builder()
                .category(category).platform("COUPANG").platformCategoryId("legacy")
                .platformCategory(platformCategory).build());
        MasterProduct master = em.persist(MasterProduct.builder()
                .name("마스터").active(true).category(category)
                .defaultDelivery(delivery).defaultPackage(box).build());
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

        // standard category = master.category; platform code = CategoryMapping; delivery = master default —
        // no LazyInit exception.
        assertThat(service().resolveStandardCategory(cell).getName()).isEqualTo("신발");
        assertThat(service().resolvePlatformCategoryCode(cell)).isEqualTo("cat-1");
        assertThat(service().resolveDelivery(cell, null).getCost()).isEqualByComparingTo("2500");
        assertThat(service().resolvePackage(cell, null).getCost()).isEqualByComparingTo("500");
    }
}
