package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestionBacktesterTest {

    @Test
    void buyThenHoldThenSell() {
        List<BacktestDay> days = List.of(
                day("2026-01-02", 100f, "BUY"),
                day("2026-01-03", 110f, "HOLD"),
                day("2026-01-06", 120f, "SELL"));

        SuggestionBacktester.Result result = SuggestionBacktester.run(10_000, days);

        assertEquals(1, result.buyCount());
        assertEquals(1, result.sellCount());
        assertEquals(0, result.endingShares());
        // 100 shares @ 100, sell @ 120 → 12_000
        assertEquals(12_000.0, result.endingEquity(), 0.01);
        assertEquals(20.0, result.returnPct(), 0.01);
    }

    @Test
    void skipBuyWhenAlreadyLong() {
        List<BacktestDay> days = List.of(
                day("2026-01-02", 100f, "BUY"),
                day("2026-01-03", 105f, "BUY"),
                day("2026-01-06", 90f, "SELL"));

        SuggestionBacktester.Result result = SuggestionBacktester.run(10_000, days);

        assertEquals(1, result.buyCount());
        assertEquals(1, result.skippedBuys());
        assertEquals(9_000.0, result.endingEquity(), 0.01);
    }

    @Test
    void avoidSellsAllShares() {
        List<BacktestDay> days = List.of(
                day("2026-01-02", 100f, "BUY"),
                day("2026-01-03", 95f, "AVOID"));

        SuggestionBacktester.Result result = SuggestionBacktester.run(10_000, days);

        assertEquals(0, result.endingShares());
        assertEquals(9_500.0, result.endingEquity(), 0.01);
        assertTrue(result.trades().stream().anyMatch(t -> "SELL_ALL".equals(t.event())));
    }

    @Test
    void avoidDoesNothingWhenCash() {
        List<BacktestDay> days = List.of(
                day("2026-01-02", 100f, "AVOID"),
                day("2026-01-03", 110f, "HOLD"));

        SuggestionBacktester.Result result = SuggestionBacktester.run(10_000, days);

        assertEquals(10_000.0, result.endingEquity(), 0.01);
        assertEquals(0, result.trades().size());
    }

    @Test
    void partsBuyOnePartThenSellOnePart() {
        // part = $2500 → 25 shares @ 100; sell one lot @ 120 → cash leftover + 3000
        List<BacktestDay> days = List.of(
                day("2026-01-02", 100f, "BUY"),
                day("2026-01-03", 110f, "HOLD"),
                day("2026-01-06", 120f, "SELL"));

        SuggestionBacktester.Result result = SuggestionBacktester.runParts(10_000, 4, days);

        assertEquals(1, result.buyCount());
        assertEquals(1, result.sellCount());
        assertEquals(0, result.endingShares());
        // start 10000; buy 25 @ 100 → cash 7500; sell 25 @ 120 → cash 10500
        assertEquals(10_500.0, result.endingEquity(), 0.01);
    }

    @Test
    void partsCanAccumulateMultipleBuys() {
        List<BacktestDay> days = List.of(
                day("2026-01-02", 100f, "BUY"),
                day("2026-01-03", 100f, "BUY"),
                day("2026-01-06", 100f, "AVOID"));

        SuggestionBacktester.Result result = SuggestionBacktester.runParts(10_000, 4, days);

        assertEquals(2, result.buyCount());
        assertEquals(1, result.sellCount());
        // two lots of 25; sell oldest 25 @ 100 → still 25 shares, cash 7500
        assertEquals(25, result.endingShares());
        assertEquals(7_500.0, result.endingCash(), 0.01);
        assertEquals(10_000.0, result.endingEquity(), 0.01);
        assertTrue(result.trades().stream().anyMatch(t -> "SELL_PART".equals(t.event())));
    }

    private static BacktestDay day(String date, float close, String action) {
        return new BacktestDay(LocalDate.parse(date), close, action);
    }
}
