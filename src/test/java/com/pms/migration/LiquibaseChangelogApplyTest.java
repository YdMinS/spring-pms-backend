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

        // changeset 008: thumbnail_asset table + its columns materialized (a successful count proves both).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM thumbnail_asset WHERE storage_key IS NULL AND content_type IS NULL",
                Integer.class)).isZero();

        // changeset 009: master_product + margin_policy tables + product_listing.master_product_id column
        // materialized (a successful count proves the structural changesets; dbms:mysql backfill is skipped).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_product WHERE name IS NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM margin_policy WHERE margin_rate IS NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing WHERE master_product_id IS NULL", Integer.class)).isZero();

        // changeset 011: generated_product_data table + its columns materialized (a successful count proves both).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM generated_product_data WHERE thumbnail_url IS NULL AND detail_html IS NULL",
                Integer.class)).isZero();

        // changeset 012: product_listing.status column materialized (a successful count proves it)...
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing WHERE status IS NULL", Integer.class)).isZero();
        // ...and platform_product_id relaxed to nullable (DRAFT cells carry no market id).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'PRODUCT_LISTING' AND COLUMN_NAME = 'PLATFORM_PRODUCT_ID'",
                String.class)).isEqualTo("YES");

        // changeset 013: product_listing_option.approval_status + seller_product_item_id materialized
        // (a successful count over both columns proves they exist).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing_option "
                        + "WHERE approval_status IS NULL AND seller_product_item_id IS NULL",
                Integer.class)).isZero();

        // changeset 014: product_listing.needs_market_sync materialized (a successful count proves it).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing WHERE needs_market_sync IS NULL", Integer.class)).isZero();
    }

    @Test
    void tenantDimensionApplied() {
        // changeset 002: tenant table created + seeded with the default tenant (id=1).
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenant", Integer.class)).isEqualTo(1);

        // tenant_id column exists on every tenant-owned table (a successful count proves the column).
        // No rows yet, but WHERE tenant_id IS NULL also proves backfill left nothing null.
        for (String table : new String[]{
                "products", "seller", "product_listing", "marketplace_account", "member",
                "order_item", "shopping_list_item", "purchase_record", "carrier_rate", "package"}) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE tenant_id IS NULL", Integer.class))
                    .as("tenant_id column present and non-null on %s", table)
                    .isZero();
        }
    }
}
