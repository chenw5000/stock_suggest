package com.stocksugg.db;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    @Test
    void createsStockTableWithExpectedColumns() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:stock_schema;DB_CLOSE_DELAY=-1")) {
            assertTrue(db.stockTableExists());

            Set<String> columns = new HashSet<>();
            try (ResultSet rs = db.connection().getMetaData().getColumns(null, null, "STOCK", null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }

            assertTrue(columns.containsAll(Set.of(
                    "id", "ticker", "date",
                    "open", "high", "low", "close",
                    "ma5", "ma10", "ma20", "ma50", "ma200",
                    "rsi14", "chandemmt", "chalkinmf",
                    "suggestedaction", "confidence",
                    "suggestedstopprice", "suggestedentryprice", "suggestedprofitprice",
                    "thesis", "risks")));
            assertEquals(22, columns.size());
        }
    }

    @Test
    void createsAdminTableWithExpectedColumns() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:admin_schema;DB_CLOSE_DELAY=-1")) {
            assertTrue(db.adminTableExists());

            Set<String> columns = new HashSet<>();
            try (ResultSet rs = db.connection().getMetaData().getColumns(null, null, "ADMIN", null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }

            assertTrue(columns.containsAll(Set.of("id", "key", "value")));
            assertEquals(3, columns.size());
        }
    }

    @Test
    void canInsertAndReadStockRow() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:stock_insert;DB_CLOSE_DELAY=-1");
             Statement stmt = db.connection().createStatement()) {
            stmt.executeUpdate("""
                    INSERT INTO stock (
                        ticker, "date", open, high, low, close,
                        ma5, ma10, ma20, ma50, ma200, rsi14, chandeMmt, chalkinMF
                    ) VALUES (
                        'AAPL', '2026-07-13', 210.0, 215.0, 209.0, 214.5,
                        212.0, 211.0, 210.0, 205.0, 190.0, 64.5, 0.25, 0.10
                    )
                    """);

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT ticker, \"date\", close, rsi14 FROM stock "
                            + "WHERE ticker = 'AAPL' ORDER BY id DESC LIMIT 1")) {
                assertTrue(rs.next());
                assertEquals("AAPL", rs.getString("ticker"));
                assertEquals("2026-07-13", rs.getString("date"));
                assertEquals(214.5f, rs.getFloat("close"), 0.001f);
                assertEquals(64.5f, rs.getFloat("rsi14"), 0.001f);
            }
        }
    }
}
