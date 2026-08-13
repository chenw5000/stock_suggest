package com.stocksugg.db;

import com.stocksugg.stock.StockDayView;
import com.stocksugg.stock.SuggestionUpdate;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestionsRepositoryTest {

    @Test
    void upsertInsertsAndUpdatesByStockAndParam() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:suggestions_upsert;DB_CLOSE_DELAY=-1");
             Statement stmt = db.connection().createStatement()) {
            stmt.executeUpdate("""
                    INSERT INTO stock (
                        ticker, "date", open, high, low, close,
                        ma5, ma10, ma20, ma50, ma200, rsi14, chandeMmt, chalkinMF
                    ) VALUES (
                        'AAPL', '2026-07-15', 210.0, 215.0, 209.0, 214.5,
                        212.0, 211.0, 210.0, 205.0, 190.0, 64.5, 0.25, 0.10
                    )
                    """);

            StockRepository stocks = new StockRepository(db);
            long stockId = stocks.findId("AAPL", LocalDate.parse("2026-07-15")).orElseThrow();
            SuggestionsRepository suggestions = new SuggestionsRepository(db);

            int n = suggestions.upsert(stockId, 1L, new SuggestionUpdate(
                    "BUY", 0.75f, 200f, 210f, 230f, "thesis-a", "risks-a"));
            assertTrue(n >= 1);

            n = suggestions.upsert(stockId, 2L, new SuggestionUpdate(
                    "AVOID", 0.55f, null, null, null, "thesis-b", "risks-b"));
            assertTrue(n >= 1);

            n = suggestions.upsert(stockId, 1L, new SuggestionUpdate(
                    "HOLD", 0.40f, 201f, 211f, 220f, "thesis-a2", "risks-a2"));
            assertTrue(n >= 1);

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM suggestions WHERE stock_id = " + stockId)) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT suggestedaction, confidence FROM suggestions "
                            + "WHERE stock_id = " + stockId + " AND param_id = 1")) {
                assertTrue(rs.next());
                assertEquals("HOLD", rs.getString(1));
                assertEquals(0.40f, rs.getFloat(2), 0.001f);
            }

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT suggestedaction FROM suggestions "
                            + "WHERE stock_id = " + stockId + " AND param_id = 2")) {
                assertTrue(rs.next());
                assertEquals("AVOID", rs.getString(1));
            }

            List<StockDayView> day = stocks.findByDate(
                    LocalDate.parse("2026-07-15"), 2L);
            assertEquals(1, day.size());
            assertEquals("AVOID", day.get(0).suggestedAction());

            List<StockDayView> missing = stocks.findByDate(
                    LocalDate.parse("2026-07-15"), 99L);
            assertEquals(1, missing.size());
            assertNull(missing.get(0).suggestedAction());
        }
    }
}
