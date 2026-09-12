package com.pms.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

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
 * (products.description, coupang_order_line.raw) legitimately diverge H2(VARCHAR/CLOB) vs MySQL(TEXT/JSON), so a
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
        // ⚠️ order_item is gone (changeset 068) — the order tables are asserted in orderModelApplied().
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller", Integer.class)).isZero();

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

        // changeset 038 added marketplace_account.vendor_user_id (71), but changeset 065 moved the four
        // Coupang credential columns to coupang_account_credential (FEATURE_2609_26) → the column is gone
        // here. See coupangAccountCredentialApplied() for the post-065 state.

        // changeset 039: marketplace_shipping_config table + columns materialized (a successful count over the
        // key columns proves the table + outbound/return/delivery structure; 72).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupang_shipping_config "
                        + "WHERE outbound_shipping_place_code IS NULL AND return_center_code IS NULL "
                        + "AND remote_area_deliverable IS NULL",
                Integer.class)).isZero();

        // changeset 042: marketplace_shipping_config.extra_info_message materialized (a successful count proves it; 75).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupang_shipping_config WHERE extra_info_message IS NULL",
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
                "SELECT COUNT(*) FROM coupang_shipping_config "
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

        // changeset 052: the two customer-name columns (FEATURE_2609_06). They moved to the order header
        // with 066 (order_item is dropped by 068), so the assertion follows them to `orders`.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders "
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
                        + "AND external_item_id IS NULL AND order_line_id IS NULL "
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
    void orderClaimTypeUniqueApplied() {
        // changeset 056: the upsert key now carries claim_type (FEATURE_2609_18 D24).
        // The old constraint must be GONE, not merely shadowed — while it stands, an exchange whose
        // exchangeId equals an existing receiptId still collides with the return row.
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'ORDER_CLAIM'", String.class);

        assertThat(constraints)
                .contains("UQ_ORDERCLAIM_ACCOUNT_TYPE_CLAIM_ITEM")
                .doesNotContain("UQ_ORDERCLAIM_ACCOUNT_CLAIM_ITEM");
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
    void listingDetailTemplateApplied() {
        // changeset 057: product_listing.detail_template_id (nullable FK → detail_template, FEATURE_2609_20).
        // No backfill on purpose — every pre-existing row stays NULL, which IS the previous 2-tier behaviour
        // (account ?? tenant default), so the count below proves both the column and that nothing was filled.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing WHERE detail_template_id IS NOT NULL",
                Integer.class)).isZero();
    }

    @Test
    void orderClaimActionApplied() {
        // changeset 058: order_claim_action + its index (FEATURE_2609_21).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_claim_action WHERE succeeded IS NULL", Integer.class)).isZero();

        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE TABLE_NAME = 'ORDER_CLAIM_ACTION'", String.class);
        assertThat(indexes).contains("IDX_ORDERCLAIMACTION_CLAIM_ACTION_SUCCEEDED");

        // No UNIQUE on purpose: retrying a failed action must be able to pile up rows, so the
        // duplicate guard is a query over succeeded=true rows rather than a database constraint.
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'ORDER_CLAIM_ACTION'", String.class);
        assertThat(constraints).doesNotContain("UNIQUE");

        // changeset 059: order_claim.collect_status added, nullable, with no backfill (existing rows
        // stay empty until the next exchange sync fills them - an empty value opens no action).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_claim WHERE collect_status IS NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'ORDER_CLAIM' AND COLUMN_NAME = 'COLLECT_STATUS'",
                String.class)).isEqualTo("YES");
    }

    @Test
    void listingOptionMasterLinkApplied() {
        // changeset 060: the master↔cell option link becomes an FK (2609_22/D1). The three new columns exist,
        // and the link column is nullable — NULL is the meaningful value "channel-only option" (D2).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_listing_option WHERE master_product_option_id IS NULL "
                        + "OR option_name_source IS NOT NULL OR category_attributes IS NULL "
                        + "OR category_notices IS NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'PRODUCT_LISTING_OPTION' "
                        + "AND COLUMN_NAME = 'MASTER_PRODUCT_OPTION_ID'", String.class)).isEqualTo("YES");
        // option_name_source is NOT NULL with default AUTO (pre-existing rows are all AUTO, like price_source).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'PRODUCT_LISTING_OPTION' "
                        + "AND COLUMN_NAME = 'OPTION_NAME_SOURCE'", String.class)).isEqualTo("NO");

        // 🔴 D22: the FK deletes NOTHING — deleting a master option must leave the cell row alive (Coupang
        // cannot remove an approved option, so the row becomes channel-only + inactive). CASCADE here would
        // silently delete live marketplace options.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS "
                        + "WHERE CONSTRAINT_NAME = 'FK_PLO_MASTER_OPTION'", String.class))
                .isEqualTo("SET NULL");
    }

    @Test
    void coupangAccountCredentialApplied() {
        // changeset 065: the credential table materialized with all its columns (FEATURE_2609_26).
        // The MySQL-only backfill is skipped on this empty H2 DB, so an empty count is what proves
        // the table + its structure exist.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupang_account_credential "
                        + "WHERE tenant_id IS NULL AND marketplace_account_id IS NULL "
                        + "AND vendor_id IS NULL AND vendor_user_id IS NULL "
                        + "AND access_key IS NULL AND secret_key IS NULL",
                Integer.class)).isZero();

        // 1:1 with the account — the UNIQUE is what keeps a second credential row out.
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'COUPANG_ACCOUNT_CREDENTIAL'", String.class);
        assertThat(constraints).contains("UQ_COUPANG_CRED_ACCOUNT");

        // ...and the core lost the four Coupang columns (a rename-style move, not an additive copy).
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT vendor_id FROM marketplace_account", String.class))
                .as("marketplace_account.vendor_id dropped by 065")
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'MARKETPLACE_ACCOUNT' "
                        + "AND COLUMN_NAME IN ('VENDOR_USER_ID', 'ACCESS_KEY', 'SECRET_KEY')",
                Integer.class)).isZero();
    }

    @Test
    void orderModelApplied() {
        // changeset 066: the neutral order core (3 tables) + the Coupang extension materialized.
        // The MySQL-only backfills are skipped on this empty H2 DB, so an empty count is what proves
        // the tables + their structure exist (FEATURE_2609_26).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE tenant_id IS NULL AND marketplace_account_id IS NULL "
                        + "AND platform IS NULL AND external_order_id IS NULL AND ordered_at IS NULL "
                        + "AND orderer_name IS NULL AND receiver_name IS NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_shipment WHERE tenant_id IS NULL AND order_id IS NULL "
                        + "AND external_shipment_id IS NULL AND shipping_fee IS NULL AND remote_fee IS NULL "
                        + "AND tracking_available IS NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_line WHERE tenant_id IS NULL AND order_id IS NULL "
                        + "AND order_shipment_id IS NULL AND status IS NULL AND item_name IS NULL "
                        + "AND order_qty IS NULL AND cancel_qty IS NULL AND hold_qty IS NULL "
                        + "AND unit_price IS NULL AND line_amount IS NULL AND discount_amount IS NULL "
                        + "AND platform_discount_amount IS NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupang_order_line WHERE tenant_id IS NULL AND order_line_id IS NULL "
                        + "AND marketplace_account_id IS NULL AND shipment_box_id IS NULL "
                        + "AND order_id_raw IS NULL AND vendor_item_id IS NULL AND platform_status IS NULL "
                        + "AND raw IS NULL", Integer.class)).isZero();

        // tenant_id is NOT NULL on all four (PLAN D25 — the extension/child tables carry it too).
        for (String table : new String[]{"ORDERS", "ORDER_SHIPMENT", "ORDER_LINE", "COUPANG_ORDER_LINE"}) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE TABLE_NAME = '" + table + "' AND COLUMN_NAME = 'TENANT_ID'", String.class))
                    .as("tenant_id NOT NULL on %s", table)
                    .isEqualTo("NO");
        }

        // 🔴 the line's natural key lives on the extension, not on the core (D3); the core order keeps
        // its own (account, orderId) uniqueness.
        List<String> coupangConstraints = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'COUPANG_ORDER_LINE'", String.class);
        assertThat(coupangConstraints).contains("UQ_COUPANG_ORDER_LINE", "UQ_COUPANG_ORDER_LINE_LINE");
        assertThat(jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_NAME = 'ORDERS'",
                String.class)).contains("UQ_ORDERS_ACCOUNT_ORDER");
        assertThat(jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'ORDER_SHIPMENT'", String.class)).contains("UQ_ORDER_SHIPMENT");

        // changeset 067: the four FK tables now carry order_line_id (a successful count proves the rename;
        // the old order_item_id column is gone with it).
        for (String table : new String[]{
                "order_claim", "customer_inquiry", "shopping_list_item", "order_cancel_action"}) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE order_line_id IS NULL", Integer.class))
                    .as("order_line_id present on %s", table)
                    .isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '"
                            + table.toUpperCase() + "' AND COLUMN_NAME = 'ORDER_ITEM_ID'", Integer.class))
                    .as("order_item_id gone from %s", table)
                    .isZero();
        }
        // 🔴 the claim backfill counter keeps its old column name on purpose (2609_18 runs on it unchanged).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'ORDER_CLAIM' "
                        + "AND COLUMN_NAME = 'ORDER_ITEM_MATCH_ATTEMPTS'", Integer.class)).isEqualTo(1);

        // changeset 068: order_item is dropped — querying it must fail.
        assertThatThrownBy(() -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_item", Integer.class))
                .isInstanceOf(DataAccessException.class);
    }

    /** changesets 077 + 078: purchase and stock both carry their own (product, seller) attribution. */
    @Test
    void purchaseAndStockSellerAxisApplied() {
        // 077: purchase_record gained product_id + seller_id (a successful count proves both columns).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_record WHERE product_id IS NULL AND seller_id IS NULL",
                Integer.class)).isZero();

        // 🔴 077-pr-drop-line-fk: the order-line link is gone for good (D3) — querying it must fail.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'PURCHASE_RECORD' "
                        + "AND COLUMN_NAME = 'SHOPPING_LIST_ITEM_ID'", Integer.class)).isZero();

        // 078: stock_movement gained seller_id, and it is mandatory (stock is not shared, D4).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement WHERE seller_id IS NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'STOCK_MOVEMENT' "
                        + "AND COLUMN_NAME = 'SELLER_ID'", String.class)).isEqualTo("NO");
    }

    /** changeset 079: the order line carries its own option link, and both backfill dialects applied. */
    @Test
    void orderLineListingOptionApplied() {
        // The column exists (a successful count proves it) and stays NULLABLE — a NOT NULL here would
        // block order sync for every line whose option cannot be matched (D15).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_line WHERE product_listing_option_id IS NULL",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'ORDER_LINE' "
                        + "AND COLUMN_NAME = 'PRODUCT_LISTING_OPTION_ID'", String.class)).isEqualTo("YES");

        // The H2 twin of the MySQL-only backfill really ran — without it this apply check would be
        // proving nothing about the statement that moves the data (the MySQL one is skipped here).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID = "
                        + "'079-order-line-listing-option-backfill-h2'", Integer.class)).isEqualTo(1);
    }

    /** changeset 082: price history table exists with the NOT NULL previous price that keeps creation out. */
    @Test
    void priceChangeLogApplied() {
        // The table and its reference columns exist (a successful count proves them).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM price_change_log WHERE product_id IS NULL "
                        + "AND product_listing_option_id IS NULL AND purchase_record_id IS NULL",
                Integer.class)).isZero();

        // 🔴 old_price NOT NULL is the schema-level half of "a first price is not a change": a nullable
        // column would quietly re-open recording creations.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'PRICE_CHANGE_LOG' "
                        + "AND COLUMN_NAME = 'OLD_PRICE'", String.class)).isEqualTo("NO");

        // Deliberately absent: the channel of a selling-price row is joined at read time, never stored.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'PRICE_CHANGE_LOG' "
                        + "AND COLUMN_NAME IN ('PLATFORM', 'LISTING_ID', 'MASTER_PRODUCT_ID')",
                Integer.class)).isZero();
    }

    /** changeset 083·084: the settlement ledger applies and keeps the two nullable links it depends on. */
    @Test
    void settlementLedgerApplied() {
        // The three neutral tables and the mirror exist (a successful count proves them).
        for (String table : new String[]{
                "settlement_payout", "settlement_line", "settlement_adjustment", "coupang_settlement_line"}) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table, Integer.class))
                    .as("table %s created", table)
                    .isZero();
        }

        // 🔴 settlement_payout_id MUST stay nullable: prompt 01 loads lines with no payout at all, and a
        // NOT NULL here would make the revenue load impossible until prompt 02 ships (PLAN D5-2).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SETTLEMENT_LINE' "
                        + "AND COLUMN_NAME = 'SETTLEMENT_PAYOUT_ID'", String.class)).isEqualTo("YES");
        // order_line_id nullable = UNMATCHED is a normal state, not a defect (PLAN D7).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SETTLEMENT_LINE' "
                        + "AND COLUMN_NAME = 'ORDER_LINE_ID'", String.class)).isEqualTo("YES");

        // The per-channel sync anchors landed on the account (nullable, no backfill: NULL = never synced).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketplace_account WHERE last_settlement_sync_at IS NULL "
                        + "AND last_payout_sync_at IS NULL", Integer.class)).isZero();
    }

    /**
     * changeset 085: revenue_recognition_month must be VARCHAR, not CHAR.
     *
     * <p>083 created it as CHAR(7) while the entity maps a String of length 7, so Hibernate's schema
     * validation refused to start on MySQL ("found [char], but expecting [varchar(7)]"). CHAR was the
     * only such column in the whole changelog. Boot failure is the real regression this guards.
     */
    @Test
    void settlementPayoutMonthIsVarchar() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SETTLEMENT_PAYOUT' "
                        + "AND COLUMN_NAME = 'REVENUE_RECOGNITION_MONTH'", String.class))
                .isEqualTo("CHARACTER VARYING");

        // The unique key that spans this column survived the type change.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'SETTLEMENT_PAYOUT' AND CONSTRAINT_NAME = 'UQ_SETTLEMENT_PAYOUT'",
                Integer.class)).isEqualTo(1);
    }

    /**
     * changeset 086: 고정비 카탈로그 + 채널 연결 (FEATURE_2609_33).
     *
     * <p>🔴 두 테이블 모두 {@code tenant_id}·{@code created_date}·{@code modified_date} 가 있어야 한다 —
     * 엔티티가 {@code BaseEntity} 를 상속하므로 빠지면 {@code ddl-auto: validate} 가 dev 부팅을 죽인다.
     */
    @Test
    void channelFixedCostApplied() {
        for (String table : new String[]{"platform_fixed_cost", "marketplace_account_fixed_cost"}) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE tenant_id IS NULL "
                            + "OR created_date IS NULL OR modified_date IS NULL", Integer.class))
                    .as("tenant_id / audit columns present on %s", table)
                    .isZero();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_NAME IN ('UK_PLATFORMFIXEDCOST_PLATFORM_NAME', "
                        + "'UK_MAFIXEDCOST_ACCOUNT_ITEM')", Integer.class)).isEqualTo(2);
    }

    /**
     * changeset 087: 택배사 코드 카탈로그 + 쿠팡 시드 (FEATURE_2609_37 / PLAN D1 · D10).
     *
     * <p>🔴 시드가 실제로 적재됐는지 확인하는 <b>유일한 자리</b>다(D12) — 다른 테스트는 Liquibase OFF 라
     * 카탈로그가 비어 있다. 표가 비면 전 건 400 이 나므로 행 수를 못 박는다.
     * <p>🔴 {@code tenant_id}·감사컬럼이 <b>없어야</b> 한다(D2) — 엔티티가 BaseEntity 를 상속하지 않아
     * 컬럼이 생기면 {@code ddl-auto: validate} 가 dev 부팅을 죽인다.
     */
    @Test
    void carrierCatalogApplied() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carrier_catalog WHERE platform = 'COUPANG'", Integer.class))
                .isEqualTo(198);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM carrier_catalog WHERE platform='COUPANG' AND code='CJGLS'", String.class))
                .isEqualTo("CJ대한통운");

        // display_order: 큐레이션 17개가 10~170, 나머지는 1000 이상 (D10-1).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carrier_catalog WHERE platform='COUPANG' AND display_order <= 170",
                Integer.class)).isEqualTo(17);

        // 롯데 두 코드는 둘 다 살아 있어야 한다 (계정마다 매칭되는 쪽이 다르다).
        assertThat(jdbcTemplate.queryForList(
                "SELECT code FROM carrier_catalog WHERE platform='COUPANG' AND code IN ('HYUNDAI','LOTTEGLOBAL')",
                String.class)).containsExactlyInAnyOrder("HYUNDAI", "LOTTEGLOBAL");

        // tenant 없는 lookup — tenant_id / 감사컬럼이 없어야 한다.
        assertThat(jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'CARRIER_CATALOG'",
                String.class))
                .containsExactlyInAnyOrder("ID", "PLATFORM", "CODE", "NAME", "DISPLAY_ORDER");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_NAME = 'UQ_CARRIER_CATALOG_PLATFORM_CODE'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void packageSizeColumnsApplied() {
        // changeset 088: a successful count proves the three columns exist
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM package WHERE width_cm IS NULL OR length_cm IS NULL OR height_cm IS NULL",
                Integer.class)).isZero();
        // and NOT NULL is really on: an insert without the sizes must fail
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "INSERT INTO package (tenant_id, type, cost, effective_date, is_default) "
                + "VALUES (1, 'X', 100, '2026-01-01', false)"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void tenantDimensionApplied() {
        // changeset 002: tenant table created + seeded with the default tenant (id=1).
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenant", Integer.class)).isEqualTo(1);

        // tenant_id column exists on every tenant-owned table (a successful count proves the column).
        // No rows yet, but WHERE tenant_id IS NULL also proves backfill left nothing null.
        for (String table : new String[]{
                "products", "seller", "product_listing", "marketplace_account", "member",
                "order_line", "shopping_list_item", "purchase_record", "carrier_rate", "package"}) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE tenant_id IS NULL", Integer.class))
                    .as("tenant_id column present and non-null on %s", table)
                    .isZero();
        }
    }
}
