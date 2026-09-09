package com.pms.migration;

import com.pms.common.TestJpaConfig;
import com.pms.domain.Category;
import com.pms.domain.Platform;
import com.pms.security.crypto.AesAttributeConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changeset 076 — the SQL that clears platform values predating the {@link Platform} enum.
 *
 * <p>Why this is a test and not just a migration: the two statements are pure {@code WHERE} clauses, and
 * a wrong one is silent. Widened by a character it would blank the platform of every live category and
 * delete every real mapping — data no rollback can restore (076 declares an empty rollback). So the
 * assertions that carry the weight are the ones showing COUPANG/NAVER rows are left alone.
 *
 * <p>⚠️ <b>Why the setup drops a constraint.</b> The two schemas disagree about this column, and that
 * disagreement is why the bug reached dev unseen. H2 here is built by Hibernate {@code ddl-auto}, which
 * emits a check constraint from the enum, so a legacy value is structurally impossible — every test
 * passed. MySQL is built by Liquibase as a plain VARCHAR with no such constraint, so rows written before
 * FEATURE_2609_26 narrowed the type kept their old marketplace name and broke hydration on read
 * ({@code No enum constant com.pms.domain.Platform.GMARKET} → 500 on GET /api/admin/category).
 * Dropping the check makes H2 behave like the database the migration actually runs against.
 */
@DataJpaTest
@Import({TestJpaConfig.class, AesAttributeConverter.class})
@ActiveProfiles("test")
class PlatformLegacyValueCleanupTest {

    /** category.platform is @deprecated(44) and nullable — blanked in place. */
    private static final String CLEAR_CATEGORY = """
            UPDATE category SET platform = NULL
             WHERE platform IS NOT NULL AND platform NOT IN ('COUPANG', 'NAVER')
            """;

    /** category_mapping.platform is NOT NULL and a live lookup — the whole row goes. */
    private static final String DELETE_MAPPING = """
            DELETE FROM category_mapping
             WHERE platform IS NOT NULL AND platform NOT IN ('COUPANG', 'NAVER')
            """;

    @Autowired
    private TestEntityManager em;

    @BeforeEach
    void retypePlatformColumnsToMatchMysql() {
        retypeToVarchar("category");
        retypeToVarchar("category_mapping");
    }

    @Test
    void testCategoryLegacyPlatformClearedAndSupportedKept() {
        Long legacy = insertCategory("옛 지마켓 카테고리", "GMARKET");
        Long coupang = insertCategory("쿠팡 카테고리", "COUPANG");
        Long naver = insertCategory("네이버 카테고리", "NAVER");

        runCleanup();

        // Readable again — before the cleanup this very load is what returned 500.
        Category cleared = em.find(Category.class, legacy);
        assertThat(cleared.getPlatform()).isNull();
        assertThat(cleared.getName()).isEqualTo("옛 지마켓 카테고리");

        assertThat(em.find(Category.class, coupang).getPlatform()).isEqualTo(Platform.COUPANG);
        assertThat(em.find(Category.class, naver).getPlatform()).isEqualTo(Platform.NAVER);
    }

    @Test
    void testMappingLegacyRowDeletedAndSupportedKept() {
        Long categoryId = insertCategory("카테고리", null);
        insertMapping(categoryId, "GMARKET", "123");
        insertMapping(categoryId, "COUPANG", "56789");

        runCleanup();

        assertThat(mappingPlatforms(categoryId)).containsExactly("COUPANG");
    }

    private void runCleanup() {
        em.flush();
        em.getEntityManager().createNativeQuery(CLEAR_CATEGORY).executeUpdate();
        em.getEntityManager().createNativeQuery(DELETE_MAPPING).executeUpdate();
        em.clear();
    }

    /** Native insert — the enum mapping cannot write a constant it does not declare. */
    private Long insertCategory(String name, String platform) {
        em.getEntityManager()
                .createNativeQuery("INSERT INTO category (name, platform) VALUES (:name, :platform)")
                .setParameter("name", name)
                .setParameter("platform", platform)
                .executeUpdate();
        return ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM category WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertMapping(Long categoryId, String platform, String platformCategoryId) {
        em.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO category_mapping (category_id, platform, platform_category_id)
                        VALUES (:categoryId, :platform, :code)
                        """)
                .setParameter("categoryId", categoryId)
                .setParameter("platform", platform)
                .setParameter("code", platformCategoryId)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private List<String> mappingPlatforms(Long categoryId) {
        return em.getEntityManager()
                .createNativeQuery("SELECT platform FROM category_mapping WHERE category_id = :id")
                .setParameter("id", categoryId)
                .getResultList();
    }

    /**
     * Hibernate builds this column as an H2 native {@code ENUM('COUPANG','NAVER')}, which rejects a
     * legacy value outright (H2 22030). Liquibase builds it in MySQL as VARCHAR(50), which accepts
     * anything — that is the database 076 has to repair, so the test widens H2 to match it.
     */
    private void retypeToVarchar(String table) {
        em.getEntityManager()
                .createNativeQuery("ALTER TABLE " + table + " ALTER COLUMN platform SET DATA TYPE VARCHAR(50)")
                .executeUpdate();
    }
}
