package com.pms.migration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * changeset 126 against a database that already holds 11st order lines without an option (FEATURE_2611_14 /
 * D6 · D10 ~ D15).
 *
 * <p>{@link LiquibaseChangelogApplyTest} applies to an EMPTY database, so 126 links nothing there. This test builds
 * the pre-126 state: apply once, drop what 126 created, forget 126 in DATABASECHANGELOG, plant listings and order
 * lines with referential integrity off, apply again — the {@link ParcelStatusLogBackfillTest} technique.</p>
 *
 * <p>Seed — account 1 = seller 10 on 11st. 11st product 「P1」: listing 100 (seller 10, 11st) with option 1001 on and
 * 1002 off; listing 101 (seller 10, Coupang, same product number) with 1011 on; listing 102 (seller 20, 11st, same
 * product number) with 1021 on. 「P2」: listings 200 · 201 (seller 10, 11st) with one option on each. 「P3」: listing
 * 300 with its only option off.</p>
 */
class ElevenstSingleOptionLinkBackfillTest {

    private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";
    private static final String CHANGESET_ID = "126-elevenst-single-option-link";
    private static final String TAG = "before-126";
    private static final String NO_NAME = "<order><ordNo>N1</ordNo><prdNo>P1</prdNo></order>";

    @Test
    void linksOnlyLinesOfSingleActiveOptionProducts_andRollbackUndoesOnlyItsOwnLinks() throws Exception {
        String url = "jdbc:h2:mem:elevenst-single-option-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            // 1) Schema, then rewind to just before 126.
            applyChangelog(url);
            exec(conn, "DROP TABLE elevenst_single_option_link_backup");
            exec(conn, "DELETE FROM DATABASECHANGELOG WHERE ID = '" + CHANGESET_ID + "'");

            // 2) Listings and lines 126 has to read.
            exec(conn, "SET REFERENTIAL_INTEGRITY FALSE");
            exec(conn, "INSERT INTO marketplace_account (id, tenant_id, seller_id, platform, is_active) "
                    + "VALUES (1, 1, 10, 'ELEVENST', TRUE)");
            insertListing(conn, 100, 10, "ELEVENST", "P1");
            insertOption(conn, 1001, 100, true);
            insertOption(conn, 1002, 100, false);
            insertListing(conn, 101, 10, "COUPANG", "P1");
            insertOption(conn, 1011, 101, true);
            insertListing(conn, 102, 20, "ELEVENST", "P1");
            insertOption(conn, 1021, 102, true);
            insertListing(conn, 200, 10, "ELEVENST", "P2");
            insertOption(conn, 2001, 200, true);
            insertListing(conn, 201, 10, "ELEVENST", "P2");
            insertOption(conn, 2011, 201, true);
            insertListing(conn, 300, 10, "ELEVENST", "P3");
            insertOption(conn, 3001, 300, false);
            // D10: any status — line 1 is already delivered.
            insertLine(conn, 1, "DELIVERED", "NULL", "'P1'", "NULL", NO_NAME);
            // D11: an empty option-name tag counts as no option name.
            insertLine(conn, 2, "PAID", "NULL", "'P1'", "NULL",
                    "<order><prdNo>P1</prdNo><slctPrdOptNm></slctPrdOptNm></order>");
            // D7: an option name is present → stays empty.
            insertLine(conn, 3, "PAID", "NULL", "'P1'", "NULL",
                    "<order><prdNo>P1</prdNo><slctPrdOptNm>검정</slctPrdOptNm></order>");
            // D2 · D10: a seller stock code is present → stays empty.
            insertLine(conn, 4, "PAID", "NULL", "'P1'", "'70'", NO_NAME);
            // D1 · D3: two active options across the two 「P2」 listings → stays empty.
            insertLine(conn, 5, "PAID", "NULL", "'P2'", "NULL", NO_NAME);
            // D3: no active option → stays empty.
            insertLine(conn, 6, "PAID", "NULL", "'P3'", "NULL", NO_NAME);
            // D4: an existing option is never overwritten.
            insertLine(conn, 7, "PAID", "1002", "'P1'", "NULL", NO_NAME);
            // D10: an order line without an 11st mirror row is not an 11st line.
            exec(conn, "INSERT INTO order_line (id, tenant_id, order_id, status, order_qty, cancel_qty, hold_qty) "
                    + "VALUES (8, 1, 900, 'PAID', 1, 0, 0)");
            exec(conn, "SET REFERENTIAL_INTEGRITY TRUE");

            // 3) Redeploy (tagged first so the rollback below reaches exactly 126).
            tag(url);
            assertThatCode(() -> applyChangelog(url)).doesNotThrowAnyException();

            // 4) D14: the backup holds the linked lines; the order lines read the same option.
            assertThat(strings(conn, "SELECT order_line_id || ':' || tenant_id || ':' || product_listing_option_id "
                    + "FROM elevenst_single_option_link_backup ORDER BY order_line_id"))
                    .containsExactly("1:1:1001", "2:1:1001");
            assertThat(options(conn)).containsExactly(
                    "1:1001", "2:1001", "3:-", "4:-", "5:-", "6:-", "7:1002", "8:-");

            // 5) D12: a further deploy runs nothing again.
            assertThatCode(() -> applyChangelog(url)).doesNotThrowAnyException();
            assertThat(count(conn, "SELECT COUNT(*) FROM elevenst_single_option_link_backup")).isEqualTo(2);

            // 6) D15: line 2 is re-linked by hand; the rollback empties line 1 only and drops the backup.
            exec(conn, "UPDATE order_line SET product_listing_option_id = 1002 WHERE id = 2");
            rollbackToTag(url);
            assertThat(options(conn)).containsExactly(
                    "1:-", "2:1002", "3:-", "4:-", "5:-", "6:-", "7:1002", "8:-");
            assertThat(count(conn, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLE_NAME = 'ELEVENST_SINGLE_OPTION_LINK_BACKUP'")).isZero();
        }
    }

    private void insertListing(Connection conn, long id, long sellerId, String platform, String prdNo)
            throws SQLException {
        exec(conn, "INSERT INTO product_listing (id, tenant_id, platform, platform_product_id, name, seller_id) "
                + "VALUES (" + id + ", 1, '" + platform + "', '" + prdNo + "', 'L" + id + "', " + sellerId + ")");
    }

    private void insertOption(Connection conn, long id, long listingId, boolean active) throws SQLException {
        exec(conn, "INSERT INTO product_listing_option (id, product_listing_id, option_name, selling_price, active) "
                + "VALUES (" + id + ", " + listingId + ", 'O" + id + "', 1000, " + (active ? "TRUE" : "FALSE") + ")");
    }

    /** {@code optionLiteral} · {@code prdNoLiteral} · {@code sellerStockCdLiteral} are raw SQL literals so NULL can be planted. */
    private void insertLine(Connection conn, long id, String status, String optionLiteral, String prdNoLiteral,
                            String sellerStockCdLiteral, String raw) throws SQLException {
        exec(conn, "INSERT INTO order_line (id, tenant_id, order_id, status, order_qty, cancel_qty, hold_qty, "
                + "product_listing_option_id) VALUES (" + id + ", 1, 900, '" + status + "', 1, 0, 0, "
                + optionLiteral + ")");
        exec(conn, "INSERT INTO elevenst_order_line (id, tenant_id, order_line_id, marketplace_account_id, ord_no, "
                + "ord_prd_seq, prd_no, seller_stock_cd, raw) VALUES (" + id + ", 1, " + id + ", 1, 'N" + id + "', '1', "
                + prdNoLiteral + ", " + sellerStockCdLiteral + ", '" + raw + "')");
    }

    private List<String> options(Connection conn) throws SQLException {
        return strings(conn, "SELECT id || ':' || COALESCE(CAST(product_listing_option_id AS VARCHAR), '-') "
                + "FROM order_line ORDER BY id");
    }

    /** New connection per Liquibase call — {@code Liquibase.close()} closes the connection it was given. */
    private void applyChangelog(String url) throws Exception {
        try (Liquibase liquibase = liquibase(url)) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }

    private void tag(String url) throws Exception {
        try (Liquibase liquibase = liquibase(url)) {
            liquibase.tag(TAG);
        }
    }

    private void rollbackToTag(String url) throws Exception {
        try (Liquibase liquibase = liquibase(url)) {
            liquibase.rollback(TAG, new Contexts(), new LabelExpression());
        }
    }

    private Liquibase liquibase(String url) throws Exception {
        Connection conn = DriverManager.getConnection(url, "sa", "");
        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(conn));
        return new Liquibase(MASTER_CHANGELOG, new ClassLoaderResourceAccessor(), database);
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private int count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private List<String> strings(Connection conn, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
