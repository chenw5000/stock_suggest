package com.stocksugg.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * One-shot migration: copies all rows from the local H2 file database into PostgreSQL.
 * Run: {@code mvn -q compile exec:java -Dexec.mainClass=com.stocksugg.db.H2ToPostgresMigrator}
 */
public final class H2ToPostgresMigrator {

    private static final String SELECT_ALL = """
            SELECT id, ticker, "date", open, high, low, close,
                   ma5, ma10, ma20, ma50, ma200, chandeMmt, chalkinMF,
                   suggestedAction, confidence,
                   suggestedStopPrice, suggestedEntryPrice, suggestedProfitPrice,
                   thesis, risks
            FROM stock
            ORDER BY id
            """;

    private static final String INSERT = """
            INSERT INTO stock (
                id, ticker, "date", open, high, low, close,
                ma5, ma10, ma20, ma50, ma200, chandeMmt, chalkinMF,
                suggestedAction, confidence,
                suggestedStopPrice, suggestedEntryPrice, suggestedProfitPrice,
                thesis, risks
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    public static void main(String[] args) throws Exception {
        DatabaseConfig source = DatabaseConfig.h2File();
        DatabaseConfig target = DatabaseConfig.postgres();

        System.out.println("Source (H2): " + source.jdbcUrl());
        System.out.println("Target (PostgreSQL): " + target.jdbcUrl());

        try (Connection h2 = DriverManager.getConnection(
                     source.jdbcUrl(), source.user(), source.password());
             Database pgDb = new Database(target);
             Connection pg = pgDb.connection()) {

            int h2Count = count(h2);
            int pgCountBefore = count(pg);
            System.out.println("H2 rows: " + h2Count);
            System.out.println("PostgreSQL rows (before): " + pgCountBefore);

            if (h2Count == 0) {
                System.out.println("Nothing to migrate.");
                return;
            }

            if (pgCountBefore > 0) {
                System.out.println("Truncating existing PostgreSQL stock table...");
                try (Statement stmt = pg.createStatement()) {
                    stmt.execute("TRUNCATE TABLE stock RESTART IDENTITY");
                }
            }

            int migrated = copyRows(h2, pg);
            syncIdentity(pg);

            int pgCountAfter = count(pg);
            System.out.println("Migrated rows: " + migrated);
            System.out.println("PostgreSQL rows (after): " + pgCountAfter);

            if (pgCountAfter != h2Count) {
                throw new IllegalStateException(
                        "Row count mismatch: H2=" + h2Count + ", PostgreSQL=" + pgCountAfter);
            }
            System.out.println("Migration complete.");
        }
    }

    private static int count(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM stock")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int copyRows(Connection h2, Connection pg) throws SQLException {
        boolean previous = pg.getAutoCommit();
        pg.setAutoCommit(false);
        int migrated = 0;
        try (PreparedStatement insert = pg.prepareStatement(INSERT);
             Statement select = h2.createStatement();
             ResultSet rs = select.executeQuery(SELECT_ALL)) {
            while (rs.next()) {
                insert.setLong(1, rs.getLong("id"));
                insert.setString(2, rs.getString("ticker"));
                insert.setString(3, rs.getString("date"));
                setFloat(insert, 4, rs, "open");
                setFloat(insert, 5, rs, "high");
                setFloat(insert, 6, rs, "low");
                setFloat(insert, 7, rs, "close");
                setFloat(insert, 8, rs, "ma5");
                setFloat(insert, 9, rs, "ma10");
                setFloat(insert, 10, rs, "ma20");
                setFloat(insert, 11, rs, "ma50");
                setFloat(insert, 12, rs, "ma200");
                setFloat(insert, 13, rs, "chandeMmt");
                setFloat(insert, 14, rs, "chalkinMF");
                setString(insert, 15, rs, "suggestedAction");
                setFloat(insert, 16, rs, "confidence");
                setFloat(insert, 17, rs, "suggestedStopPrice");
                setFloat(insert, 18, rs, "suggestedEntryPrice");
                setFloat(insert, 19, rs, "suggestedProfitPrice");
                setString(insert, 20, rs, "thesis");
                setString(insert, 21, rs, "risks");
                insert.addBatch();
                migrated++;
                if (migrated % 500 == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
            pg.commit();
            return migrated;
        } catch (SQLException e) {
            pg.rollback();
            throw e;
        } finally {
            pg.setAutoCommit(previous);
        }
    }

    private static void syncIdentity(Connection pg) throws SQLException {
        try (Statement stmt = pg.createStatement()) {
            stmt.execute("""
                    SELECT setval(
                        pg_get_serial_sequence('stock', 'id'),
                        COALESCE((SELECT MAX(id) FROM stock), 1),
                        (SELECT COUNT(*) > 0 FROM stock)
                    )
                    """);
        }
    }

    private static void setFloat(PreparedStatement ps, int index, ResultSet rs, String column)
            throws SQLException {
        float value = rs.getFloat(column);
        if (rs.wasNull()) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setFloat(index, value);
        }
    }

    private static void setString(PreparedStatement ps, int index, ResultSet rs, String column)
            throws SQLException {
        String value = rs.getString(column);
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
