package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.PlatformCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PlatformCategoryRepository} against a real DB (FEATURE_2608_06 / 52): the finders resolve, the
 * (platform, code) unique index is enforced, and multiple intermediate nodes with {@code code = null} coexist
 * (NULL is not covered by the unique index on MySQL / H2 MODE=MySQL).
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class PlatformCategoryRepositoryTest {

    @Autowired private PlatformCategoryRepository repository;
    @Autowired private TestEntityManager em;

    private PlatformCategory node(String code, String name, PlatformCategory parent) {
        // Do NOT set tenantId: @TenantId stamps it (NO_TENANT in this @DataJpaTest slice, self-consistent).
        return PlatformCategory.builder()
                .platform("COUPANG").code(code).name(name).parent(parent)
                .commissionRate(code == null ? null : new BigDecimal("0.10")).build();
    }

    @Test
    void finders_resolveTreeByCodeParentAndRoot() {
        PlatformCategory root = em.persist(node(null, "패션", null));
        PlatformCategory leaf = em.persist(node("cat-1", "운동화", root));
        em.flush();
        em.clear();

        assertThat(repository.findByPlatformAndCode("COUPANG", "cat-1"))
                .get().extracting(PlatformCategory::getName).isEqualTo("운동화");
        assertThat(repository.findByParentId(root.getId()))
                .extracting(PlatformCategory::getName).containsExactly("운동화");
        assertThat(repository.findByParentIsNullAndPlatform("COUPANG"))
                .extracting(PlatformCategory::getName).containsExactly("패션");
        assertThat(leaf.getId()).isNotNull();
    }

    @Test
    void uniquePlatformCode_secondRowWithSameCode_violates() {
        em.persist(node("dup", "A", null));
        em.flush();

        // IDENTITY generation inserts eagerly, so the duplicate (platform, code) trips on persist/flush.
        assertThatThrownBy(() -> {
            em.persist(node("dup", "B", null));
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    void nullCode_multipleIntermediateNodesCoexist() {
        // Intermediate nodes carry code = null; multiple NULLs are allowed by the (platform, code) unique index.
        em.persist(node(null, "중간1", null));
        em.persist(node(null, "중간2", null));

        em.flush(); // must not throw
        assertThat(repository.findByParentIsNullAndPlatform("COUPANG")).hasSize(2);
    }
}
