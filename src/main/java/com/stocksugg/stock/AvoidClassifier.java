package com.stocksugg.stock;

import java.util.Locale;

/**
 * Splits a Gemini {@code AVOID} label into {@code HIGH} (overextended / take profit) and
 * {@code LOW} (weak / broken) from that day's indicators. This is a heuristic of our own,
 * looser than Gemini's "Parabolic Blow-off" rule; Gemini itself only returns plain AVOID.
 */
public final class AvoidClassifier {

    public static final String HIGH = "HIGH";
    public static final String LOW = "LOW";

    /** RSI at or above this marks an AVOID as overextended. */
    public static final float HIGH_RSI = 60f;

    /** Close above {@code ma20 * this} marks an AVOID as overextended. */
    public static final float HIGH_MA20_STRETCH = 1.05f;

    private AvoidClassifier() {}

    /**
     * {@link #HIGH} or {@link #LOW} for an AVOID day; null for other actions or when
     * RSI and close/ma20 are all missing.
     */
    public static String side(String suggestedAction, Float rsi14, Float close, Float ma20) {
        if (!isAvoid(suggestedAction)) {
            return null;
        }
        boolean hasRsi = rsi14 != null;
        boolean hasStretch = close != null && ma20 != null;
        if ((hasRsi && rsi14 >= HIGH_RSI) || (hasStretch && close > ma20 * HIGH_MA20_STRETCH)) {
            return HIGH;
        }
        if (!hasRsi && !hasStretch) {
            return null;
        }
        return LOW;
    }

    /** {@code AVOID_HIGH} / {@code AVOID_LOW} for classified AVOIDs, else the upper-cased action. */
    public static String actionKey(String suggestedAction, String avoidSide) {
        if (suggestedAction == null || suggestedAction.isBlank()) {
            return suggestedAction;
        }
        String action = suggestedAction.trim().toUpperCase(Locale.ROOT);
        if ("AVOID".equals(action) && avoidSide != null) {
            return action + "_" + avoidSide;
        }
        return action;
    }

    private static boolean isAvoid(String suggestedAction) {
        return suggestedAction != null
                && "AVOID".equals(suggestedAction.trim().toUpperCase(Locale.ROOT));
    }
}
