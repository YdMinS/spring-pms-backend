package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MasterProductRepository#searchActivePage} against a real DB (FEATURE_2608_06 / 110,
 * FEATURE_2609_60).
 *
 * <p>The search is one JPQL that ORs three things: the master name
 * ({@code lower(...) like lower(concat('%', :keyword, '%'))}) and two exact id matches reached through
 * correlated {@code exists} subqueries. Case folding, the active filter, the correlation and the
 * "one row per master" guarantee only prove themselves in real SQL, so a mocked service test cannot
 * cover them.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class MasterProductRepositoryTest {

    @Autowired private MasterProductRepository repository;
    @Autowired private TestEntityManager em;

    @Test
    void searchActivePage_matchesPartialCaseInsensitive() {
        // Do NOT set tenantId: @TenantId stamps it (NO_TENANT in this slice) and filters reads with the same value.
        em.persist(MasterProduct.builder().name("커피A").active(true).build());
        em.persist(MasterProduct.builder().name("디카페인 커피").active(true).build());
        em.persist(MasterProduct.builder().name("KOFFEE Blend").active(true).build());
        em.flush();

        Page<MasterProduct> hits = repository.searchActivePage("커피", PageRequest.of(0, 25));
        assertThat(hits.getTotalElements()).isEqualTo(2);
        assertThat(hits.getContent()).extracting(MasterProduct::getName)
                .containsExactlyInAnyOrder("커피A", "디카페인 커피");

        // Case-insensitive both ways: a lowercase keyword matches an uppercase name.
        assertThat(repository.searchActivePage("koffee", PageRequest.of(0, 25)).getContent())
                .extracting(MasterProduct::getName).containsExactly("KOFFEE Blend");
    }

    @Test
    void searchActivePage_excludesInactive() {
        em.persist(MasterProduct.builder().name("커피 활성").active(true).build());
        em.persist(MasterProduct.builder().name("커피 삭제됨").active(false).build());
        em.flush();

        Page<MasterProduct> hits = repository.searchActivePage("커피", PageRequest.of(0, 25));

        assertThat(hits.getContent()).extracting(MasterProduct::getName).containsExactly("커피 활성");
    }

    @Test
    void searchActivePage_matchesPlatformProductId() {
        givenMasterWithListing(true);

        Page<MasterProduct> hits = repository.searchActivePage("1234567", PageRequest.of(0, 25));

        assertThat(hits.getContent()).extracting(MasterProduct::getName).containsExactly("생수 2L");
    }

    @Test
    void searchActivePage_matchesPlatformOptionId() {
        givenMasterWithListing(true);

        Page<MasterProduct> hits = repository.searchActivePage("8123456789", PageRequest.of(0, 25));

        assertThat(hits.getContent()).extracting(MasterProduct::getName).containsExactly("생수 2L");
    }

    @Test
    void searchActivePage_exactMatchOnly() {
        givenMasterWithListing(true);

        // A numeric id under `like %..%` would drag in every longer id that merely contains it (D2).
        assertThat(repository.searchActivePage("812", PageRequest.of(0, 25)).getContent()).isEmpty();
        assertThat(repository.searchActivePage("123", PageRequest.of(0, 25)).getContent()).isEmpty();
    }

    @Test
    void searchActivePage_matchesInactiveOption() {
        // "Which master owns this id" is not "is it selling" (D4) — an inactive option still matches.
        givenMasterWithListing(false);

        Page<MasterProduct> hits = repository.searchActivePage("8123456789", PageRequest.of(0, 25));

        assertThat(hits.getContent()).extracting(MasterProduct::getName).containsExactly("생수 2L");
    }

    @Test
    void searchActivePage_returnsMasterOnceWhenTwoOptionsShareTheId() {
        // platform_option_id carries no unique constraint and duplicates really exist
        // (079-order-line-listing-option.yaml works around them with MIN(plo.id)). `exists` must not
        // multiply the master — a `join` would return it twice and break the page count.
        ProductListing cell = givenMasterWithListing(true);
        em.persist(ProductListingOption.builder()
                .productListing(cell).optionName("12입")
                .sellingPrice(new BigDecimal("18000")).platformOptionId("8123456789").build());
        em.flush();

        Page<MasterProduct> hits = repository.searchActivePage("8123456789", PageRequest.of(0, 25));

        assertThat(hits.getTotalElements()).isEqualTo(1);
    }

    /**
     * One master that owns a cell + option, plus a second master with no listing at all.
     *
     * <p>🔴 The listing-less "other master" is not optional. With a single master in the table the
     * correlation ({@code l.masterProduct = m}) could be deleted and every id test would still pass:
     * "return everything" and "return one row" would be indistinguishable. That is precisely this
     * query's worst failure mode (any id returns the whole list).</p>
     */
    private ProductListing givenMasterWithListing(boolean optionActive) {
        Seller seller = em.persist(Seller.builder()
                .sellerName("셀러A").businessRegistration("111-11-11111").build());
        MasterProduct master = em.persist(MasterProduct.builder().name("생수 2L").active(true).build());
        ProductListing cell = em.persist(ProductListing.builder()
                .seller(seller).platform(Platform.COUPANG).name("생수 2L")
                .masterProduct(master).platformProductId("1234567").build());
        em.persist(ProductListingOption.builder()
                .productListing(cell).optionName("6입").active(optionActive)
                .sellingPrice(new BigDecimal("10000")).platformOptionId("8123456789").build());

        em.persist(MasterProduct.builder().name("생수 500ml").active(true).build());
        em.flush();
        return cell;
    }
}
