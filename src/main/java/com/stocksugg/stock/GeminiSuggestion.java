package com.stocksugg.stock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.stream.Collectors;

/** Parsed Gemini suggestion response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiSuggestion(
        String ticker,
        String asOf,
        String action,
        Float confidence,
        String entryPriceRange,
        String cutlossPriceRange,
        String profitTakingPriceRange,
        Integer horizonTradingDays,
        List<String> thesis,
        KeyLevels keyLevels,
        List<String> risks,
        String invalidation,
        List<String> dataUsed
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeyLevels(Float support, Float resistance) {}

    public SuggestionUpdate toDbUpdate() {
        return new SuggestionUpdate(
                PriceRangeParser.normalizeAction(action),
                confidence,
                PriceRangeParser.midpoint(cutlossPriceRange).orElse(null),
                PriceRangeParser.midpoint(entryPriceRange).orElse(null),
                PriceRangeParser.midpoint(profitTakingPriceRange).orElse(null),
                joinLines(thesis),
                joinLines(risks));
    }

    private static String joinLines(List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .collect(Collectors.joining("\n"));
    }
}
