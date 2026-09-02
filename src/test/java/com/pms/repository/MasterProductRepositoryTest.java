package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MasterProduct;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MasterProductRepository#searchActiveByNamePage} against a real DB (FEATURE_2608_06 / 110).
 *
 * <p>The name search is a {@code lower(...) like lower(concat('%', :keyword, '%'))} JPQL — case folding and
 * the active filter only prove themselves in real SQL, so a mocked service test cannot cover them.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class MasterProductRepositoryTest {

    @Autowired private MasterProductRepository repository;
    @Autowired private TestEntityManager em;

    @Test
    void searchActiveByNamePage_matchesPartialCaseInsensitive() {
        // Do NOT set tenantId: @TenantId stamps it (NO_TENANT in this slice) and filters reads with the same value.
        em.persist(MasterProduct.builder().name("커피A").active(true).build());
        em.persist(MasterProduct.builder().name("디카페인 커피").active(true).build());
        em.persist(MasterProduct.builder().name("KOFFEE Blend").active(true).build());
        em.flush();

        Page<MasterProduct> hits = repository.searchActiveByNamePage("커피", PageRequest.of(0, 25));
        assertThat(hits.getTotalElements()).isEqualTo(2);
        assertThat(hits.getContent()).extracting(MasterProduct::getName)
                .containsExactlyInAnyOrder("커피A", "디카페인 커피");

        // Case-insensitive both ways: a lowercase keyword matches an uppercase name.
        assertThat(repository.searchActiveByNamePage("koffee", PageRequest.of(0, 25)).getContent())
                .extracting(MasterProduct::getName).containsExactly("KOFFEE Blend");
    }

    @Test
    void searchActiveByNamePage_excludesInactive() {
        em.persist(MasterProduct.builder().name("커피 활성").active(true).build());
        em.persist(MasterProduct.builder().name("커피 삭제됨").active(false).build());
        em.flush();

        Page<MasterProduct> hits = repository.searchActiveByNamePage("커피", PageRequest.of(0, 25));

        assertThat(hits.getContent()).extracting(MasterProduct::getName).containsExactly("커피 활성");
    }
}
