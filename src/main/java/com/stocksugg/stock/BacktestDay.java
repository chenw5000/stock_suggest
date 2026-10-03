package com.stocksugg.stock;

import java.time.LocalDate;

/**
 * One trading day used by the suggestion backtester.
 *
 * @param avoidSide {@link AvoidClassifier#HIGH} / {@link AvoidClassifier#LOW} when the action
 *                  is AVOID and indicators allow a split; otherwise null
 */
public record BacktestDay(
        LocalDate date,
        float close,
        String suggestedAction,
        Float confidence,
        String avoidSide
) {
    public BacktestDay(LocalDate date, float close, String suggestedAction) {
        this(date, close, suggestedAction, null, null);
    }

    public BacktestDay(LocalDate date, float close, String suggestedAction, Float confidence) {
        this(date, close, suggestedAction, confidence, null);
    }

    /** {@code AVOID_HIGH} / {@code AVOID_LOW} for classified AVOIDs, else the action. */
    public String actionKey() {
        return AvoidClassifier.actionKey(suggestedAction, avoidSide);
    }
}
