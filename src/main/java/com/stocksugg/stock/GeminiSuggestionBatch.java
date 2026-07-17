package com.stocksugg.stock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Batch response from Gemini for multiple tickers. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiSuggestionBatch(List<GeminiSuggestion> suggestions) {}
