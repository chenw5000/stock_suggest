package com.stocksugg.insider;

import com.stocksugg.stock.StockRow;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Body for {@code POST /api/impact} on the insiderTrans service. */
public record InsiderImpactRequest(
        String ticker,
        String asOfDate,
        List<InsiderImpactBar> bars
) {
    public static InsiderImpactRequest of(String ticker, LocalDate asOf, List<StockRow> rows) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        if (asOf == null) {
            throw new IllegalArgumentException("asOf is required");
        }
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("bars are required");
        }
        List<InsiderImpactBar> bars = rows.stream().map(InsiderImpactBar::from).toList();
        return new InsiderImpactRequest(ticker.trim().toUpperCase(Locale.ROOT), asOf.toString(), bars);
    }
}
