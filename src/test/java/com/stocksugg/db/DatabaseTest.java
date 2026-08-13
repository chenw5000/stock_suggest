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
    void createsTuningParamsAndSuggestionsTables() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:tuning_schema;DB_CLOSE_DELAY=-1");
             Statement stmt = db.connection().createStatement()) {
            assertTrue(db.tuningParamsTableExists());
            assertTrue(db.suggestionsTableExists());

            Set<String> paramCols = new HashSet<>();
            try (ResultSet rs = db.connection().getMetaData()
                    .getColumns(null, null, "TUNING_PARAMS", null)) {
                while (rs.next()) {
                    paramCols.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertTrue(paramCols.containsAll(Set.of(
                    "id", "num_date_point",
                    "ma5_weight", "ma10_weight", "ma20_weight", "ma50_weight", "ma200_weight",
                    "rsi_weight", "cmo_weight", "cmf_weight",
                    "name", "description")));

            Set<String> suggCols = new HashSet<>();
            try (ResultSet rs = db.connection().getMetaData()
                    .getColumns(null, null, "SUGGESTIONS", null)) {
                while (rs.next()) {
                    suggCols.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertTrue(suggCols.containsAll(Set.of(
                    "id", "stock_id", "param_id",
                    "suggestedaction", "confidence",
                    "suggestedstopprice", "suggestedentryprice", "suggestedprofitprice",
                    "thesis", "risks")));

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT name, num_date_point FROM tuning_params WHERE name = 'DEFAULT-40'")) {
                assertTrue(rs.next());
                assertEquals("DEFAULT-40", rs.getString("name"));
                assertEquals(40, rs.getInt("num_date_point"));
            }
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
