package com.stocksugg.stock;

import java.time.LocalDate;
import java.util.List;

/** Full stock row including Gemini suggestion fields (for UI / reports). */
public record StockDayView(
        long id,
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
        Float chalkinMF,
        String suggestedAction,
        Float confidence,
        Float suggestedStopPrice,
        Float suggestedEntryPrice,
        Float suggestedProfitPrice,
        List<String> thesis,
        List<String> risks
) {}
