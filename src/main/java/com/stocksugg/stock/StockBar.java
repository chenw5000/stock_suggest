package com.stocksugg.stock;

import java.time.LocalDate;

/** One daily OHLCV bar. */
public record StockBar(
        String ticker,
        LocalDate date,
        double open,
        double high,
        double low,
        double close,
        long volume
) {}
