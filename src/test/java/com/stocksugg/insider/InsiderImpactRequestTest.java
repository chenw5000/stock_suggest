package com.stocksugg.insider;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.stock.StockRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsiderImpactRequestTest {

    @Test
    void ofMapsStockRowsToImpactBars() {
        List<StockRow> rows = List.of(
                new StockRow("aapl", LocalDate.parse("2026-10-01"),
                        100f, 101f, 99f, 100.5f,
                        null, null, null, null, null, null, null, null, 1_000_000L),
                new StockRow("aapl", LocalDate.parse("2026-10-02"),
                        100.5f, 102f, 100f, 101f,
                        null, null, null, null, null, null, null, null, 2_000_000L));

        InsiderImpactRequest request = InsiderImpactRequest.of("aapl", LocalDate.parse("2026-10-02"), rows);

        assertEquals("AAPL", request.ticker());
        assertEquals("2026-10-02", request.asOfDate());
        assertEquals(2, request.bars().size());
        assertEquals("2026-10-01", request.bars().getFirst().date());
        assertEquals(100.0, request.bars().getFirst().open(), 1e-9);
        assertEquals(1_000_000L, request.bars().getFirst().volume());
        assertEquals(2_000_000L, request.bars().getLast().volume());
    }

    @Test
    void ofRejectsEmptyBars() {
        assertThrows(IllegalArgumentException.class,
                () -> InsiderImpactRequest.of("AAPL", LocalDate.parse("2026-10-02"), List.of()));
    }

    @Test
    void serviceBuildsRequestFromStoredBars() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:insider_impact;DB_CLOSE_DELAY=-1")) {
            LocalDate asOf = insertBars(db, "QQQ", LocalDate.parse("2026-04-01"), 120);
            InsiderImpactService service = new InsiderImpactService(db);

            InsiderImpactRequest request = service.buildRequest("QQQ", asOf);

            assertEquals("QQQ", request.ticker());
            assertEquals(asOf.toString(), request.asOfDate());
            assertEquals(120, request.bars().size());
            assertEquals("2026-04-01", request.bars().getFirst().date());
            assertTrue(request.bars().getLast().volume() > 0);
        }
    }

    @Test
    void serviceRejectsShortHistory() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:insider_short;DB_CLOSE_DELAY=-1")) {
            LocalDate asOf = insertBars(db, "QQQ", LocalDate.parse("2026-09-01"), 10);
            InsiderImpactService service = new InsiderImpactService(db);
            assertThrows(IllegalStateException.class, () -> service.buildRequest("QQQ", asOf));
        }
    }

    /** Inserts {@code count} consecutive calendar days; returns the last date. */
    private static LocalDate insertBars(Database db, String ticker, LocalDate start, int count)
            throws Exception {
        List<StockRow> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LocalDate date = start.plusDays(i);
            float close = 100f + i;
            rows.add(new StockRow(
                    ticker, date, close, close + 1, close - 1, close,
                    null, null, null, null, null, 50f, null, null, 1_000L + i));
        }
        LocalDate last = start.plusDays(count - 1L);
        new StockRepository(db).replaceRange(ticker, start, last, rows);
        return last;
    }
}
