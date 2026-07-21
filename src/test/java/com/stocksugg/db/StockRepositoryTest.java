package com.stocksugg.db;

import com.stocksugg.stock.StockRow;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockRepositoryTest {

    @Test
    void replaceRangeDoesNotOverwriteExistingRowsOrSuggestions() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:stock_preserve;DB_CLOSE_DELAY=-1");
             Statement stmt = db.connection().createStatement()) {
            stmt.executeUpdate("""
                    INSERT INTO stock (
                        ticker, "date", open, high, low, close,
                        ma5, ma10, ma20, ma50, ma200, chandeMmt, chalkinMF,
                        suggestedAction, confidence, thesis, risks
                    ) VALUES (
                        'AAPL', '2026-07-15', 210.0, 215.0, 209.0, 214.5,
                        212.0, 211.0, 210.0, 205.0, 190.0, 0.25, 0.10,
                        'BUY', 0.8, 'keep thesis', 'keep risks'
                    )
                    """);

            StockRepository repository = new StockRepository(db);
            int inserted = repository.replaceRange(
                    "AAPL",
                    LocalDate.parse("2026-07-15"),
                    LocalDate.parse("2026-07-16"),
                    List.of(
                            row("AAPL", "2026-07-15", 999.0f),
                            row("AAPL", "2026-07-16", 220.0f)));

            assertEquals(1, inserted);
            assertEquals(2, repository.countByTicker("AAPL"));
            assertEquals(62f, repository.findAllBars("AAPL").getLast().rsi14(), 0.001f);
            assertEquals(62f, repository.findRecentBars("AAPL", 10).getLast().rsi14(), 0.001f);
            assertEquals(62f, repository.findBarsEndingOn(
                    "AAPL", LocalDate.parse("2026-07-16"), 10).getLast().rsi14(), 0.001f);
            assertEquals(62f, repository.findBarsInRange(
                    "AAPL",
                    LocalDate.parse("2026-07-16"),
                    LocalDate.parse("2026-07-16")).getFirst().rsi14(), 0.001f);
            assertEquals(62f, repository.findByDate(
                    LocalDate.parse("2026-07-16")).getFirst().rsi14(), 0.001f);
            assertEquals(62f, (Float) repository.findHistoryPage(
                    "AAPL", 1, 10).getFirst().get("rsi14"), 0.001f);

            Map<LocalDate, Float> rsiValues = new LinkedHashMap<>();
            rsiValues.put(LocalDate.parse("2026-07-15"), null);
            rsiValues.put(LocalDate.parse("2026-07-16"), 71f);
            assertEquals(2, repository.updateRsi14("AAPL", rsiValues));
            List<StockRow> updatedRows = repository.findAllBars("AAPL");
            assertNull(updatedRows.getFirst().rsi14());
            assertEquals(71f, updatedRows.getLast().rsi14(), 0.001f);

            try (ResultSet rs = stmt.executeQuery("""
                    SELECT close, thesis, risks, suggestedAction
                    FROM stock
                    WHERE ticker = 'AAPL' AND "date" = '2026-07-15'
                    """)) {
                assertTrue(rs.next());
                assertEquals(214.5f, rs.getFloat("close"), 0.001f);
                assertEquals("keep thesis", rs.getString("thesis"));
                assertEquals("keep risks", rs.getString("risks"));
                assertEquals("BUY", rs.getString("suggestedAction"));
            }
        }
    }

    private static StockRow row(String ticker, String date, float close) {
        return new StockRow(
                ticker,
                LocalDate.parse(date),
                close, close, close, close,
                null, null, null, null, null, 62f, null, null);
    }
}
