package com.pms.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard (§7) for the Liquibase master changelog — APPLICABILITY check.
 *
 * Boots the full context against an EMPTY unique H2 (MODE=MySQL) with:
 *   - spring.liquibase.enabled = true       -> the master changelog is applied
 *   - spring.jpa.hibernate.ddl-auto = none  -> Hibernate touches nothing; only Liquibase builds the schema
 *
 * A successful context boot proves the changelog (baseline + future changesets) applies cleanly to a
 * blank DB with no SQL errors — catching baseline/changeset regressions early in CI.
 *
 * This is deliberately ddl-auto=none, NOT validate: entity<->baseline fidelity (§8-6) was verified against
 * this same Hibernate-derived baseline and is documented in DECISIONS. The two large-text columns
 * (products.description, order_item.raw) legitimately diverge H2(VARCHAR/CLOB) vs MySQL(TEXT/JSON), so a
 * portable CLOB baseline cannot pass H2 validate on those columns — hence apply-check here.
 *
 * Base config keeps liquibase disabled (create-drop everywhere else), so this test overrides it locally.
 * Depends on 01's removal of schema.sql (a stray schema.sql would collide with the Liquibase-created tables).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.liquibase.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
class LiquibaseChangelogApplyTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void changelogAppliesToEmptyDatabase() {
        // Context boot already proved clean apply. Assert Liquibase recorded the baseline changesets...
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DATABASECHANGELOG", Integer.class);
        assertThat(applied).isNotNull().isGreaterThanOrEqualTo(1);

        // ...and that baseline tables actually materialized (querying proves existence).
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_item", Integer.class)).isZero();
    }
}
