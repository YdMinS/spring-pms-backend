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
 * changeset 124-2 against a database that already holds closed parcels (FEATURE_2611_03 / PLAN D35 ~ D38).
 *
 * <p>🔴 {@link LiquibaseChangelogApplyTest} applies to an EMPTY database, so 124-2 moves nothing there. This test
 * builds the pre-124 state: apply once, drop what 124 created, forget 124 in DATABASECHANGELOG, plant parcels,
 * apply again — the {@link PurchasePlaceMigrationTest} technique. Parcels are planted with referential integrity
 * off, so no order / shipment rows are needed.</p>
 */
class ParcelStatusLogBackfillTest {

    private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";

    @Test
    void closedParcelsGetOneRowEach() throws Exception {
        String url = "jdbc:h2:mem:parcel-status-log-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            // 1) Schema, then rewind to just before 124.
            applyChangelog(url);
            exec(conn, "DROP TABLE shipment_parcel_status_log");
            exec(conn, "DELETE FROM DATABASECHANGELOG WHERE ID LIKE '124-%'");

            // 2) The parcels 124-2 has to read (tenant 1 is seeded by 002).
            exec(conn, "SET REFERENTIAL_INTEGRITY FALSE");
            insertParcel(conn, 1, "INV-PENDING", "PENDING", "NULL", "NULL");
            insertParcel(conn, 2, "INV-PACKED", "PACKED", "'2026-10-30 14:00:00'", "'packer@oclyx.com'");
            insertParcel(conn, 3, "INV-UNUSED", "UNUSED", "'2026-11-02 09:30:00'", "'packer@oclyx.com'");
            insertParcel(conn, 4, "INV-UNUSED-NOBODY", "UNUSED", "'2026-11-02 09:40:00'", "NULL");
            insertParcel(conn, 5, "INV-PACKED-NOTIME", "PACKED", "NULL", "'packer@oclyx.com'");
            exec(conn, "SET REFERENTIAL_INTEGRITY TRUE");

            // 3) Redeploy.
            assertThatCode(() -> applyChangelog(url)).doesNotThrowAnyException();

            // 4) D35 ~ D37: one row per closed parcel with packed_at — from = PENDING, to = its status,
            //    person / time = its packed_by / packed_at. D38: no packed_at → no row. A PENDING parcel → no row.
            assertThat(strings(conn, "SELECT shipment_parcel_id || ':' || tenant_id || ':' || from_status || '>' "
                    + "|| to_status || ':' || COALESCE(changed_by, '-') || ':' || created_date || ':' || modified_date "
                    + "FROM shipment_parcel_status_log ORDER BY shipment_parcel_id"))
                    .containsExactly(
                            "2:1:PENDING>PACKED:packer@oclyx.com:2026-10-30 14:00:00:2026-10-30 14:00:00",
                            "3:1:PENDING>UNUSED:packer@oclyx.com:2026-11-02 09:30:00:2026-11-02 09:30:00",
                            "4:1:PENDING>UNUSED:-:2026-11-02 09:40:00:2026-11-02 09:40:00");

            // 5) Idempotent: a further deploy runs nothing again.
            assertThatCode(() -> applyChangelog(url)).doesNotThrowAnyException();
            assertThat(count(conn, "SELECT COUNT(*) FROM shipment_parcel_status_log")).isEqualTo(3);
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

    /** {@code packedAtLiteral} / {@code packedByLiteral} are raw SQL literals so NULL can be planted. */
    private void insertParcel(Connection conn, long id, String invoice, String status, String packedAtLiteral,
                              String packedByLiteral) throws SQLException {
        exec(conn, "INSERT INTO shipment_parcel (id, tenant_id, order_shipment_id, invoice_number, parcel_seq, status, "
                + "packed_at, packed_by) VALUES (" + id + ", 1, 999, '" + invoice + "', " + id + ", '" + status + "', "
                + packedAtLiteral + ", " + packedByLiteral + ")");
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
