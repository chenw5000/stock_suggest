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
        Float chandeMmt,
        Float chalkinMF
) {}
