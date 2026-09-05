package com.pms.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        // changeset 015: detail_template + master_product_image tables + their columns materialized
        // (a successful count over the columns proves both tables and their structure).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM detail_template WHERE name IS NULL AND blocks IS NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_product_image "
                        + "WHERE zone_id IS NULL AND sort_order IS NULL AND image_url IS NULL",
                Integer.class)).isZero();

        // changeset 016: generated_product_data.source materialized (a successful count proves it).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM generated_product_data WHERE source IS NULL", Integer.class)).isZero();

        // changeset 017: product_listing.field_values materialized (a successful count proves it).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing WHERE field_values IS NULL", Integer.class)).isZero();

        // changeset 020: marketplace_account.thumbnail_template_id + detail_template_id materialized
        // (a successful count over both columns proves they exist).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketplace_account "
                        + "WHERE thumbnail_template_id IS NULL AND detail_template_id IS NULL",
                Integer.class)).isZero();

        // changeset 021: generated_product_data.thumbnail_source materialized (a successful count proves it).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM generated_product_data WHERE thumbnail_source IS NULL",
                Integer.class)).isZero();

        // changeset 022: master_product.detail_source dropped (INFORMATION_SCHEMA no longer lists the column).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'MASTER_PRODUCT' AND COLUMN_NAME = 'DETAIL_SOURCE'",
                Integer.class)).isZero();

        // changeset 023: master_product.tags + product_listing.tags columns + product_listing_tag_revision
        // table materialized (a successful count over each proves both columns and the table).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_product WHERE tags IS NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing WHERE tags IS NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing_tag_revision WHERE tags IS NULL", Integer.class)).isZero();

        // changeset 024: master_image_zone_assignment table + columns materialized (a successful count proves it)...
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_image_zone_assignment "
                        + "WHERE master_product_image_id IS NULL AND zone_id IS NULL AND sort_order IS NULL",
                Integer.class)).isZero();
        // ...and master_product_image.zone_id relaxed to nullable (pool asset; mapping owns zone membership).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'MASTER_PRODUCT_IMAGE' AND COLUMN_NAME = 'ZONE_ID'",
                String.class)).isEqualTo("YES");

        // changeset 025: product_image table + columns materialized (a successful count proves it).
        // The backfill inserts nothing on the empty apply-check DB (no products).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_image "
                        + "WHERE product_id IS NULL AND sort_order IS NULL AND image_url IS NULL",
                Integer.class)).isZero();

        // changeset 027: processing_preset table + columns materialized (a successful count proves it)...
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processing_preset WHERE name IS NULL AND operations IS NULL",
                Integer.class)).isZero();
        // ...and detail_template.image_processing_preset_id column materialized (the seeder committed a
        // default detail_template row, so assert the column exists via INFORMATION_SCHEMA rather than a
        // null count).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'DETAIL_TEMPLATE' AND COLUMN_NAME = 'IMAGE_PROCESSING_PRESET_ID'",
                Integer.class)).isEqualTo(1);

        // changeset 028: product_listing_option.active materialized (a successful count proves it; the empty
        // apply-check DB has no options, so the NOT-NULL backfill leaves nothing null).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing_option WHERE active IS NULL", Integer.class)).isZero();

        // changeset 030: coupang_fee_reference table + columns materialized. The CoupangFeeReferenceSeeder
        // (a startup ApplicationRunner, not the changeset) populates it, so assert the seeded row count.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupang_fee_reference", Integer.class)).isEqualTo(125);

        // changeset 031: master_product.category_attributes + category_notices materialized
        // (a successful count over both columns proves they exist).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_product "
                        + "WHERE category_attributes IS NULL AND category_notices IS NULL",
                Integer.class)).isZero();

        // changeset 032: platform_category table + its columns materialized (a successful count over the
        // columns proves the table + code/commission structure)...
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_category "
                        + "WHERE platform IS NULL AND name IS NULL AND code IS NULL "
                        + "AND parent_id IS NULL AND commission_rate IS NULL",
                Integer.class)).isZero();
        // ...and category_mapping.platform_category_id_fk (the FK promotion column) materialized.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category_mapping WHERE platform_category_id_fk IS NULL",
                Integer.class)).isZero();

        // changeset 034: master_product_option.category_attributes + category_notices materialized
        // (a successful count over both columns proves they exist; per-option meta override, 59).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_product_option "
                        + "WHERE category_attributes IS NULL AND category_notices IS NULL",
                Integer.class)).isZero();

        // changeset 037: option_check_suffix_enabled + option_check_suffix materialized on all 3 tables (a
        // successful count over both columns proves they exist; nullable = inherit, 69).
        for (String table : new String[]{"seller", "marketplace_account", "master_product"}) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table
                            + " WHERE option_check_suffix_enabled IS NULL AND option_check_suffix IS NULL",
                    Integer.class))
                    .as("069 suffix columns present on %s", table)
                    .isZero();
        }

        // changeset 038: marketplace_account.vendor_user_id materialized (a successful count proves it; 71).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketplace_account WHERE vendor_user_id IS NULL", Integer.class)).isZero();

        // changeset 039: marketplace_shipping_config table + columns materialized (a successful count over the
        // key columns proves the table + outbound/return/delivery structure; 72).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketplace_shipping_config "
                        + "WHERE outbound_shipping_place_code IS NULL AND return_center_code IS NULL "
                        + "AND remote_area_deliverable IS NULL",
                Integer.class)).isZero();

        // changeset 042: marketplace_shipping_config.extra_info_message materialized (a successful count proves it; 75).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketplace_shipping_config WHERE extra_info_message IS NULL",
                Integer.class)).isZero();

        // changeset 043: shipping_override materialized on master_product + product_listing (75; a successful
        // count over each proves the column exists).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_product WHERE shipping_override IS NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing WHERE shipping_override IS NULL", Integer.class)).isZero();

        // changeset 044: master_product.category_notice_group materialized (91; a successful count proves it).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_product WHERE category_notice_group IS NULL", Integer.class)).isZero();

        // changeset 045: the FREE-shipping backfill applied (96 ⑧). No seeded rows here, so what this asserts
        // is that the two conditional UPDATEs ran without error and left no FREE row with a null charge.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketplace_shipping_config "
                        + "WHERE delivery_charge_type = 'FREE' "
                        + "AND (delivery_charge IS NULL OR free_ship_over_amount IS NULL)",
                Integer.class)).isZero();

        // changeset 046: the products columns were renamed (98). A successful count over all five new
        // names proves they exist...
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM products "
                        + "WHERE net_content IS NULL AND net_content_unit IS NULL "
                        + "AND package_height IS NULL AND package_length IS NULL AND package_width IS NULL",
                Integer.class)).isZero();
        // ...and the old names are gone (a rename, not an additive copy).
        assertThatThrownBy(() -> jdbcTemplate.queryForObject("SELECT weight FROM products", String.class))
                .as("old products.weight column dropped by the rename")
                .isInstanceOf(DataAccessException.class);

        // changeset 047: the audit columns exist on both listing tables (104 Step 1). A successful count over
        // all four new names proves it; they are nullable, so no backfilled value is asserted.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing "
                        + "WHERE created_date IS NULL AND modified_date IS NULL",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing_option "
                        + "WHERE created_date IS NULL AND modified_date IS NULL",
                Integer.class)).isZero();

        // changeset 048: the web-font columns exist on font_asset (105). Both are nullable, so a successful
        // count over the two new names is what proves they were added.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM font_asset WHERE web_stack IS NULL AND web_url IS NULL",
                Integer.class)).isNotNull();

        // changeset 049: stock_quantity exists on both option tables (102). Nullable with no backfill on
        // purpose — NULL means "unset/inherit" — so a successful count over the new name is the proof.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_product_option WHERE stock_quantity IS NULL",
                Integer.class)).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing_option WHERE stock_quantity IS NULL",
                Integer.class)).isNotNull();

        // changeset 050: detail_image_group materialized with all its columns (FEATURE_2609_03). The
        // table starts empty — the catalog is backfilled by DetailImageGroupSeeder at startup, not here.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM detail_image_group "
                        + "WHERE tenant_id IS NULL AND code IS NULL AND name IS NULL AND sort_order IS NULL",
                Integer.class)).isZero();

        // changeset 051: the five sync-status columns exist on marketplace_account (FEATURE_2609_02).
        // All nullable with no backfill on purpose — NULL means "never synced yet" — so a successful
        // count over the new names is what proves they were added.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketplace_account "
                        + "WHERE last_sync_status IS NULL AND last_sync_at IS NULL "
                        + "AND last_order_sync_at IS NULL AND last_cancel_sync_at IS NULL "
                        + "AND last_sync_error IS NULL",
                Integer.class)).isNotNull();

        // changeset 052: the two customer-name columns exist on order_item (FEATURE_2609_06).
        // Nullable with no backfill on purpose — the next sync's upsert fills orders inside the sync
        // window — so a successful count over the new names is what proves they were added.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_item "
                        + "WHERE orderer_name IS NULL AND receiver_name IS NULL",
                Integer.class)).isNotNull();
    }

    @Test
    void orderClaimApplied() {
        // changeset 053: order_claim table + its columns materialized (FEATURE_2609_18).
        // The table starts empty — rows are written only by the return sync's claim ingest.
        // A successful count over the new column names is what proves the table was created.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_claim "
                        + "WHERE tenant_id IS NULL AND marketplace_account_id IS NULL "
                        + "AND claim_type IS NULL AND external_claim_id IS NULL "
                        + "AND external_item_id IS NULL AND order_item_id IS NULL "
                        + "AND order_item_match_attempts IS NULL AND status IS NULL "
                        + "AND platform_status IS NULL AND received_at IS NULL AND synced_at IS NULL",
                Integer.class)).isZero();
    }

    @Test
    void listingOptionPriceSourceApplied() {
        // changeset 054: price_source exists on product_listing_option and defaults to AUTO (FEATURE_2609_19).
        // NOT NULL with defaultValue AUTO, so the query below proves both the column and that pre-existing
        // rows would read as calculated prices (which is exactly today's behaviour).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing_option WHERE price_source <> 'AUTO'",
                Integer.class)).isZero();
    }

    @Test
    void claimSyncColumnApplied() {
        // changeset 055: last_claim_sync_at exists on marketplace_account (FEATURE_2609_18 D6·D18).
        // Nullable with no backfill on purpose — NULL means "never completed a claim run", which makes the
        // first run fall back to the cancel-sync-days window — so a successful count proves it was added.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketplace_account WHERE last_claim_sync_at IS NULL",
                Integer.class)).isNotNull();
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
