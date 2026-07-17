package com.stocksugg.stock;

/** Suggestion fields persisted onto a stock row. */
public record SuggestionUpdate(
        String suggestedAction,
        Float confidence,
        Float suggestedStopPrice,
        Float suggestedEntryPrice,
        Float suggestedProfitPrice,
        String thesis,
        String risks
) {}
