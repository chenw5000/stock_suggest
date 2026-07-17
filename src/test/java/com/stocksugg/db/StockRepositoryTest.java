package com.stocksugg.db;

import com.stocksugg.stock.StockRow;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                null, null, null, null, null, null, null);
    }
}
