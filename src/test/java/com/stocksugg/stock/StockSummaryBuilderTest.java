package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockSummaryBuilderTest {

    @Test
    void buildsPackageWithLatestAndBars() {
        List<StockRow> bars = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            float close = 100f + i;
            bars.add(new StockRow(
                    "AAPL",
                    LocalDate.of(2026, 7, 1).plusDays(i),
                    close, close + 1, close - 1, close,
                    close, close, 100f, 95f, 90f,
                    55f, 10f, 0.1f));
        }

        Map<String, Object> payload = StockSummaryBuilder.buildPackage(bars, 10);
        assertEquals("AAPL", payload.get("ticker"));
        assertEquals("2026-07-06", payload.get("asOf"));
        assertTrue(payload.containsKey("latest"));
        assertEquals(6, ((List<?>) payload.get("bars")).size());

        @SuppressWarnings("unchecked")
        Map<String, Object> latest = (Map<String, Object>) payload.get("latest");
        assertEquals(105f, (Float) latest.get("close"), 0.001f);
        assertEquals(55f, (Float) latest.get("rsi14"), 0.001f);
        assertTrue(latest.containsKey("return5dPct"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payloadBars = (List<Map<String, Object>>) payload.get("bars");
        assertEquals(55f, (Float) payloadBars.getLast().get("rsi14"), 0.001f);
    }

    @Test
    void buildsMultiPackage() {
        List<StockRow> aapl = List.of(new StockRow(
                "AAPL", LocalDate.of(2026, 7, 14),
                100f, 101f, 99f, 100.5f,
                100f, 99f, 98f, 97f, 90f, 55f, 10f, 0.1f));
        List<StockRow> tsla = List.of(new StockRow(
                "TSLA", LocalDate.of(2026, 7, 14),
                200f, 201f, 199f, 200.5f,
                200f, 199f, 198f, 197f, 180f, 45f, 5f, 0.05f));

        Map<String, Object> payload = StockSummaryBuilder.buildMultiPackage(
                Map.of("AAPL", aapl, "TSLA", tsla), 10);

        assertEquals(10, payload.get("horizonTradingDays"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stocks = (List<Map<String, Object>>) payload.get("stocks");
        assertEquals(2, stocks.size());
    }
}
