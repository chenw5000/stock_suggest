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
}
