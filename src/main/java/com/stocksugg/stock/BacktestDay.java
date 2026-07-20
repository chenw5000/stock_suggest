package com.stocksugg.stock;

import java.time.LocalDate;

/** One trading day used by the suggestion backtester. */
public record BacktestDay(
        LocalDate date,
        float close,
        String suggestedAction,
        Float confidence
) {
    public BacktestDay(LocalDate date, float close, String suggestedAction) {
        this(date, close, suggestedAction, null);
    }
}
