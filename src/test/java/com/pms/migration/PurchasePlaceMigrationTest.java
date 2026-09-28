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
 * changeset 102 against a database that already holds products with free-text {@code store} values
 * (FEATURE_2609_76 / D10 · D11).
 *
 * <p>🔴 {@link LiquibaseChangelogApplyTest} applies to an EMPTY database, so 102-2 moves nothing there. This test
 * builds the pre-102 state instead: apply once, drop what 102 created, forget 102 in DATABASECHANGELOG, plant
 * products, apply again. Same technique as {@link BlankBarcodeMigrationTest} — a private H2 URL over plain JDBC,
 * no Spring context.</p>
 */
class PurchasePlaceMigrationTest {

    private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";

    @Test
    void storeValuesBecomePurchasePlaceLinks() throws Exception {
        String url = "jdbc:h2:mem:purchase-place-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            // 1) Schema, then rewind to just before 102.
            applyChangelog(url);
            exec(conn, "DROP TABLE product_purchase_place");
            exec(conn, "DROP TABLE purchase_place");
            exec(conn, "ALTER TABLE products DROP COLUMN count_quantity");
            exec(conn, "ALTER TABLE products DROP COLUMN count_unit");
            exec(conn, "DELETE FROM DATABASECHANGELOG WHERE ID LIKE '102-%'");

            // 2) The data 102-2 has to move (tenant 1 is seeded by 002; tenant 2 is added here).
            exec(conn, "INSERT INTO tenant (id, name) VALUES (2, '두 번째 업체')");
            insertProduct(conn, 1, "공백 낀 기본값", "' 이마트 '", true);
            insertProduct(conn, 1, "기본값", "'코스트코'", true);
            insertProduct(conn, 1, "목록 밖 값 1", "'동네마트'", true);
            insertProduct(conn, 1, "목록 밖 값 2", "'동네마트'", true);
            insertProduct(conn, 1, "빈 값", "''", true);
            insertProduct(conn, 1, "NULL", "NULL", true);
            insertProduct(conn, 1, "삭제된 물품", "'노브랜드'", false);
            insertProduct(conn, 2, "다른 업체", "'이마트'", true);
            insertProduct(conn, 1, "조합 값", "'노브랜드/이마트'", true);

            // 3) Redeploy.
            assertThatCode(() -> applyChangelog(url)).doesNotThrowAnyException();

            // 4) Lists: tenant 1 = three defaults + 동네마트 once; tenant 2 = three defaults.
            //    '노브랜드/이마트' adds no place of its own (PLAN D10 보강).
            assertThat(strings(conn, "SELECT name FROM purchase_place WHERE tenant_id = 1 ORDER BY sort_order, id"))
                    .containsExactly("이마트", "코스트코", "노브랜드", "동네마트");
            assertThat(strings(conn, "SELECT name FROM purchase_place WHERE tenant_id = 2 ORDER BY sort_order, id"))
                    .containsExactly("이마트", "코스트코", "노브랜드");
            assertThat(count(conn, "SELECT COUNT(*) FROM purchase_place WHERE name LIKE '%/%'")).isZero();

            // 5) Links: every non-blank store value is linked, in its own tenant, to the trimmed name;
            //    a value with '/' is linked to each of its pieces.
            assertThat(strings(conn, "SELECT p.product_name || '=' || pp.name FROM product_purchase_place l "
                    + "JOIN products p ON p.id = l.product_id JOIN purchase_place pp ON pp.id = l.purchase_place_id "
                    + "WHERE pp.tenant_id = p.tenant_id ORDER BY p.id, pp.sort_order"))
                    .containsExactly("공백 낀 기본값=이마트", "기본값=코스트코", "목록 밖 값 1=동네마트",
                            "목록 밖 값 2=동네마트", "삭제된 물품=노브랜드", "다른 업체=이마트",
                            "조합 값=이마트", "조합 값=노브랜드");
            assertThat(count(conn, "SELECT COUNT(*) FROM product_purchase_place")).isEqualTo(8);

            // 6) D11 — the old column is left exactly as it was.
            assertThat(count(conn, "SELECT COUNT(*) FROM products WHERE store = ' 이마트 '")).isEqualTo(1);

            // 7) Idempotent: a further deploy runs nothing again.
            assertThatCode(() -> applyChangelog(url)).doesNotThrowAnyException();
            assertThat(count(conn, "SELECT COUNT(*) FROM purchase_place")).isEqualTo(7);
        }
    }

    /** New connection per apply — {@code Liquibase.close()} closes the connection it was given. */
    private void applyChangelog(String url) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase(
                    MASTER_CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    /** {@code storeLiteral} is a raw SQL literal so '' and NULL can both be planted. */
    private void insertProduct(Connection conn, long tenantId, String name, String storeLiteral, boolean active)
            throws SQLException {
        exec(conn, "INSERT INTO products (tenant_id, product_name, store, active) VALUES ("
                + tenantId + ", '" + name + "', " + storeLiteral + ", " + (active ? "TRUE" : "FALSE") + ")");
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
