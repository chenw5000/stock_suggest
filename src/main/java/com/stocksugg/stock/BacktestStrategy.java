package com.stocksugg.stock;

/**
 * Configurable mapping from Gemini labels to portfolio intents, plus position sizing.
 *
 * @param parts               cash split into N equal budgets (lots); {@code 1} ≈ all-in sizing
 * @param minBuyConfidence    minimum confidence to execute a buy intent (null confidence = 0)
 * @param minSellConfidence   minimum confidence to execute a sell intent
 * @param onBuy               what to do when suggestedAction is BUY
 * @param onSell              what to do when suggestedAction is SELL
 * @param onHold              what to do when suggestedAction is HOLD (usually {@link TradeIntent#NONE})
 * @param onAvoid             what to do when suggestedAction is AVOID
 */
public record BacktestStrategy(
        int parts,
        double minBuyConfidence,
        double minSellConfidence,
        TradeIntent onBuy,
        TradeIntent onSell,
        TradeIntent onHold,
        TradeIntent onAvoid
) {
    public enum TradeIntent {
        NONE,
        BUY_PART,
        BUY_ALL,
        SELL_PART,
        SELL_ALL
    }

    public static BacktestStrategy allIn() {
        return new BacktestStrategy(
                1, 0, 0,
                TradeIntent.BUY_ALL,
                TradeIntent.SELL_ALL,
                TradeIntent.NONE,
                TradeIntent.SELL_ALL);
    }

    public static BacktestStrategy equalParts(int parts) {
        return new BacktestStrategy(
                parts, 0, 0,
                TradeIntent.BUY_PART,
                TradeIntent.SELL_PART,
                TradeIntent.NONE,
                TradeIntent.SELL_PART);
    }

    public TradeIntent intentFor(String suggestedAction) {
        return switch (normalize(suggestedAction)) {
            case "BUY" -> onBuy;
            case "SELL" -> onSell;
            case "AVOID" -> onAvoid;
            default -> onHold;
        };
    }

    public boolean passesConfidence(String suggestedAction, Float confidence) {
        TradeIntent intent = intentFor(suggestedAction);
        if (intent == TradeIntent.NONE) {
            return true;
        }
        double conf = confidence == null ? 0.0 : confidence;
        return switch (intent) {
            case BUY_PART, BUY_ALL -> conf + 1e-9 >= minBuyConfidence;
            case SELL_PART, SELL_ALL -> conf + 1e-9 >= minSellConfidence;
            case NONE -> true;
        };
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "HOLD";
        }
        return raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    @Override
    public String toString() {
        return "parts=" + parts
                + " buyConf>=" + format(minBuyConfidence)
                + " sellConf>=" + format(minSellConfidence)
                + " BUY->" + onBuy
                + " SELL->" + onSell
                + " HOLD->" + onHold
                + " AVOID->" + onAvoid;
    }

    private static String format(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
