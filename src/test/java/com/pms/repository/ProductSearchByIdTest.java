package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.Product;
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
 * {@link ProductRepository#searchByKeyword} against a real DB.
 *
 * <p>The search is one JPQL that ORs a case-insensitive partial match on name/brand/description with an
 * <b>exact</b> match on the product's own oclyx id. Only real SQL shows that the id match is exact (a
 * shorter number must not drag in the longer ids containing it) and that the {@code :idValue IS NOT NULL}
 * guard actually binds when the parameter is null — a mocked service test cannot cover either.</p>
 *
 * <p>Ids are pinned with a native UPDATE after the insert: {@code @GeneratedValue(IDENTITY)} ignores any
 * id handed to {@code persist()}, and the "longer id" case needs two ids in a known prefix relation.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class ProductSearchByIdTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 25);

    @Autowired private ProductRepository repository;
    @Autowired private TestEntityManager em;

    @Test
    void matchesTheProductIdExactly() {
        givenProductWithId(9001L, "생수 2L", true);
        givenProductWithId(90011L, "아몬드 초코볼", true);

        Page<Product> hits = repository.searchByKeyword("9001", 9001L, FIRST_PAGE);

        // 🔴 90011 contains 9001; under `like %..%` it would come back too.
        assertThat(hits.getContent()).extracting(Product::getProductName).containsExactly("생수 2L");
    }

    @Test
    void stillMatchesNameBrandAndDescription() {
        em.persist(Product.builder().productName("KOFFEE Blend").active(true).build());
        em.persist(Product.builder().productName("생수 2L").brand("삼다수").active(true).build());
        em.persist(Product.builder().productName("초코볼").description("커피 향 초콜릿").active(true).build());
        em.flush();

        // Case-insensitive, partial, and across all three text columns.
        assertThat(repository.searchByKeyword("koffee", null, FIRST_PAGE).getContent())
                .extracting(Product::getProductName).containsExactly("KOFFEE Blend");
        assertThat(repository.searchByKeyword("삼다", null, FIRST_PAGE).getContent())
                .extracting(Product::getProductName).containsExactly("생수 2L");
        assertThat(repository.searchByKeyword("커피 향", null, FIRST_PAGE).getContent())
                .extracting(Product::getProductName).containsExactly("초코볼");
    }

    /**
     * A null {@code idValue} is what a non-numeric keyword — and a number too large for a {@code Long} —
     * arrives as. The query must still run and simply ignore the id branch.
     */
    @Test
    void nullIdValueLeavesTheTextMatchAlone() {
        givenProductWithId(9001L, "생수 2L", true);

        assertThat(repository.searchByKeyword("9001", null, FIRST_PAGE).getContent()).isEmpty();
        assertThat(repository.searchByKeyword("생수", null, FIRST_PAGE).getContent()).hasSize(1);
    }

    /** The two branches are ORed: a numeric keyword still finds the names that contain those digits. */
    @Test
    void numericKeywordMatchesIdAndNameTogether() {
        givenProductWithId(9001L, "생수 2L", true);
        em.persist(Product.builder().productName("9001 스페셜 블렌드").active(true).build());
        em.flush();

        Page<Product> hits = repository.searchByKeyword("9001", 9001L, FIRST_PAGE);

        assertThat(hits.getContent()).extracting(Product::getProductName)
                .containsExactlyInAnyOrder("생수 2L", "9001 스페셜 블렌드");
    }

    /** A soft-deleted product must not come back to life through its id. */
    @Test
    void doesNotMatchAnInactiveProductById() {
        givenProductWithId(9001L, "삭제된 물품", false);

        assertThat(repository.searchByKeyword("9001", 9001L, FIRST_PAGE).getContent()).isEmpty();
    }

    private void givenProductWithId(long id, String productName, boolean active) {
        Product saved = em.persist(Product.builder().productName(productName).active(active).build());
        em.flush();
        em.getEntityManager()
                .createNativeQuery("update products set id = :newId where id = :oldId")
                .setParameter("newId", id)
                .setParameter("oldId", saved.getId())
                .executeUpdate();
        em.clear();
    }
}
