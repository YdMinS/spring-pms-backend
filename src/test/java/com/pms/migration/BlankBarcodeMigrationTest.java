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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2026-09-23 프로덕션 장애의 회귀 그물 — 빈 문자열 바코드가 남아 있는 DB 에 유일 키가 걸리는가.
 *
 * <p>🔴 {@link LiquibaseChangelogApplyTest} 는 <b>빈 DB</b> 에 changelog 를 적용하므로 이 사고를 절대
 * 재현하지 못한다. 사고는 "이미 데이터가 들어 있는 DB"에서만 일어났다: {@code barcode_id = ''} 인 물품이
 * 여러 개라 098 의 addUniqueConstraint 가
 * {@code Duplicate entry '1-' for key 'products.uq_products_tenant_barcode'} 로 죽었다. MySQL 은 NULL
 * 중복은 허용하지만 '' 는 평범한 값으로 센다. 098 의 preCondition 은 {@code <> ''} 로 빈 값을 <b>빼고</b>
 * 세어 통과했고 — 검사 기준과 제약 기준이 어긋난 것이 근본 원인이다.</p>
 *
 * <p>그래서 이 테스트는 사고 당시의 DB 상태를 직접 만든다: changelog 를 한 번 적용해 스키마를 만든 뒤,
 * 유일 키를 떼고 100·098 의 실행 기록을 지워 <b>098 직전</b>으로 되감고, 빈 문자열 바코드 행을 심은 다음
 * changelog 를 다시 적용한다. changeset 100 이 없으면 이 두 번째 적용이 1062 로 실패한다.</p>
 *
 * <p>Spring 컨텍스트를 쓰지 않는다 — 전용 H2 URL 하나에 JDBC 로 직접 붙는다. 다른 테스트의 공유 DB 를
 * 되감는 짓을 하지 않기 위해서다.</p>
 */
class BlankBarcodeMigrationTest {

    private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";

    @Test
    void uniqueKeyAppliesToDatabaseThatStillHoldsBlankBarcodes() throws Exception {
        String url = "jdbc:h2:mem:blank-barcode-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            // 1) 스키마를 만든다(빈 DB 적용 — 여기서는 사고가 일어날 수 없다).
            applyChangelog(url);

            // 2) 프로덕션이 있던 자리로 되감는다: 유일 키를 떼고 100·098 을 "아직 안 돈" 상태로 만든다.
            //    prod 는 098 이 실패했으므로 DATABASECHANGELOG 에 기록되지 않았다 — 같은 상태다.
            exec(conn, "ALTER TABLE products DROP CONSTRAINT uq_products_tenant_barcode");
            exec(conn, "DELETE FROM DATABASECHANGELOG WHERE ID IN "
                    + "('100-normalize-blank-barcode', '098-product-barcode-unique')");

            // 3) 사고를 일으킨 데이터 — 빈 문자열 바코드 2건 이상(하나는 삭제된 물품).
            insertProduct(conn, "레거시 빈바코드1", "''", true);
            insertProduct(conn, "레거시 빈바코드2", "''", true);
            insertProduct(conn, "삭제된 빈바코드", "''", false);
            insertProduct(conn, "정상 바코드", "'8801234567890'", true);
            insertProduct(conn, "바코드 없음(NULL)", "NULL", true);

            // 4) 다시 배포 = changelog 재적용. changeset 100 이 없으면 여기서 1062 로 죽는다.
            assertThatCode(() -> applyChangelog(url))
                    .as("빈 문자열 바코드가 남아 있어도 유일 키 추가가 성공해야 한다")
                    .doesNotThrowAnyException();

            // 5) '' 는 전부 NULL 이 됐고, 진짜 바코드는 그대로다.
            assertThat(count(conn, "SELECT COUNT(*) FROM products WHERE barcode_id = ''")).isZero();
            assertThat(count(conn, "SELECT COUNT(*) FROM products WHERE barcode_id IS NULL")).isEqualTo(4);
            assertThat(count(conn, "SELECT COUNT(*) FROM products WHERE barcode_id = '8801234567890'"))
                    .isEqualTo(1);

            // 6) 유일 키는 실제로 걸려 있다 — 같은 테넌트의 중복 바코드는 거부된다.
            assertThatThrownBy(() -> insertProduct(conn, "중복 바코드", "'8801234567890'", true))
                    .isInstanceOf(SQLException.class);

            // 7) 두 changeset 모두 기록됐다(= 다음 배포에서 재시도되지 않는다).
            assertThat(count(conn, "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID IN "
                    + "('100-normalize-blank-barcode', '098-product-barcode-unique')")).isEqualTo(2);

            // 8) 멱등성 — 같은 changelog 를 또 돌려도(이미 NULL) 아무 일도 일어나지 않는다.
            assertThatCode(() -> applyChangelog(url)).doesNotThrowAnyException();
        }
    }

    /**
     * ⚠️ 적용마다 <b>새 커넥션</b>을 연다 — {@code Liquibase.close()} 가 넘겨받은 커넥션까지 닫기 때문에
     * 테스트가 들고 있는 커넥션을 재사용하면 다음 SQL 에서 "object is already closed" 가 난다.
     * ({@code DB_CLOSE_DELAY=-1} 이라 커넥션이 닫혀도 in-memory DB 자체는 살아 있다.)
     */
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

    /** {@code barcodeLiteral} 은 SQL 리터럴 그대로다 — {@code ''} 와 {@code NULL} 을 구분해 넣기 위해서. */
    private void insertProduct(Connection conn, String name, String barcodeLiteral, boolean active)
            throws SQLException {
        exec(conn, "INSERT INTO products (tenant_id, product_name, barcode_id, active) VALUES "
                + "(1, '" + name + "', " + barcodeLiteral + ", " + (active ? "TRUE" : "FALSE") + ")");
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
}
