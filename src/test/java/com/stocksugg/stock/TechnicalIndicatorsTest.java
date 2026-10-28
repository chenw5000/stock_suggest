package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TechnicalIndicatorsTest {

    @Test
    void smaNeedsFullWindow() {
        double[] values = {1, 2, 3, 4, 5, 6};
        Float[] ma3 = TechnicalIndicators.sma(values, 3);
        assertNull(ma3[1]);
        assertEquals(2f, ma3[2], 0.001f);
        assertEquals(3f, ma3[3], 0.001f);
        assertEquals(5f, ma3[5], 0.001f);
    }

    @Test
    void relativeStrengthIndexUsesWilderSmoothing() {
        double[] close = {1, 2, 3, 2, 4};

        Float[] rsi = TechnicalIndicators.relativeStrengthIndex(close, 3);

        assertNull(rsi[2]);
        assertEquals(66.6667f, rsi[3], 0.001f);
        assertEquals(83.3333f, rsi[4], 0.001f);
    }

    @Test
    void relativeStrengthIndexHandlesNoLossAndFlatPrices() {
        Float[] rising = TechnicalIndicators.relativeStrengthIndex(
                new double[] {1, 2, 3, 4}, 3);
        Float[] flat = TechnicalIndicators.relativeStrengthIndex(
                new double[] {5, 5, 5, 5}, 3);

        assertEquals(100f, rising[3], 0.001f);
        assertEquals(50f, flat[3], 0.001f);
    }

    @Test
    void enrichComputesMovingAverages() {
        List<StockBar> bars = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bars.add(new StockBar(
                    "TEST",
                    LocalDate.of(2024, 1, 1).plusDays(i),
                    10 + i,
                    11 + i,
                    9 + i,
                    10 + i,
                    1_000_000L));
        }

        List<StockRow> rows = TechnicalIndicators.enrich(bars);
        assertEquals(10, rows.size());
        assertNull(rows.get(3).ma5());
        assertNotNull(rows.get(4).ma5());
        assertEquals(12f, rows.get(4).ma5(), 0.001f);
    }

    @Test
    void enrichComputesTwentyDayAverageVolume() {
        List<StockBar> bars = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            bars.add(new StockBar(
                    "TEST",
                    LocalDate.of(2024, 1, 1).plusDays(i),
                    10, 11, 9, 10,
                    1_000_000L + i * 10_000L));
        }

        List<StockRow> rows = TechnicalIndicators.enrich(bars);

        assertNull(rows.get(18).vol20());
        assertNotNull(rows.get(19).vol20());
        // days 0..19: volumes 1_000_000, 1_010_000, ... 1_190_000 → avg = 1_095_000
        assertEquals(1_095_000.0, rows.get(19).vol20(), 0.001);
        // days 5..24: 1_050_000 .. 1_240_000 → avg = 1_145_000
        assertEquals(1_145_000.0, rows.get(24).vol20(), 0.001);
        assertEquals(1_000_000L + 24 * 10_000L, rows.get(24).volume());
    }
}
