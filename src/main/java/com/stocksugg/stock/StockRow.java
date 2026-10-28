package com.stocksugg.stock;

import java.time.LocalDate;

/** Fully computed stock row ready to persist. */
public record StockRow(
        String ticker,
        LocalDate date,
        Float open,
        Float high,
        Float low,
        Float close,
        Float ma5,
        Float ma10,
        Float ma20,
        Float ma50,
        Float ma200,
        Float rsi14,
        Float chandeMmt,
        Float chalkinMF,
        Long volume,
        Double vol20
) {
    /** Compatibility constructor when volume / vol20 are unavailable. */
    public StockRow(
            String ticker,
            LocalDate date,
            Float open,
            Float high,
            Float low,
            Float close,
            Float ma5,
            Float ma10,
            Float ma20,
            Float ma50,
            Float ma200,
            Float rsi14,
            Float chandeMmt,
            Float chalkinMF) {
        this(ticker, date, open, high, low, close, ma5, ma10, ma20, ma50, ma200,
                rsi14, chandeMmt, chalkinMF, null, null);
    }

    /** Compatibility constructor when the 20-day average volume is unavailable. */
    public StockRow(
            String ticker,
            LocalDate date,
            Float open,
            Float high,
            Float low,
            Float close,
            Float ma5,
            Float ma10,
            Float ma20,
            Float ma50,
            Float ma200,
            Float rsi14,
            Float chandeMmt,
            Float chalkinMF,
            Long volume) {
        this(ticker, date, open, high, low, close, ma5, ma10, ma20, ma50, ma200,
                rsi14, chandeMmt, chalkinMF, volume, null);
    }

    /** Volume for APIs that require it; {@code 0} when unknown. */
    public long volumeOrZero() {
        return volume == null ? 0L : volume;
    }
}
