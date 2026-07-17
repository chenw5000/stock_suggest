package com.stocksugg.stock;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Gemini price-range strings like {@code "300 - 310"} into a midpoint float. */
public final class PriceRangeParser {

    private static final Pattern RANGE =
            Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*-\\s*(-?\\d+(?:\\.\\d+)?)");

    private PriceRangeParser() {}

    public static Optional<Float> midpoint(String range) {
        if (range == null || range.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = RANGE.matcher(range.trim());
        if (!matcher.find()) {
            return Optional.empty();
        }
        float low = Float.parseFloat(matcher.group(1));
        float high = Float.parseFloat(matcher.group(2));
        return Optional.of((low + high) / 2f);
    }

    public static String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "AVOID";
        }
        return action.trim().toUpperCase(Locale.ROOT);
    }
}
