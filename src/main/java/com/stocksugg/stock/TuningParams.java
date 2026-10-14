package com.stocksugg.stock;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row from {@code tuning_params}: reusable Gemini experiment settings.
 * Lookback for OHLCV packages is {@link #numDatePoint()}.
 */
public record TuningParams(
        long id,
        Integer numDatePoint,
        Integer ma5Weight,
        Integer ma10Weight,
        Integer ma20Weight,
        Integer ma50Weight,
        Integer ma200Weight,
        Integer rsiWeight,
        Integer cmoWeight,
        Integer cmfWeight,
        String name,
        String description,
        String promptVersion,
        Double temperature,
        Integer seed
) {
    public TuningParams(
            long id,
            Integer numDatePoint,
            Integer ma5Weight,
            Integer ma10Weight,
            Integer ma20Weight,
            Integer ma50Weight,
            Integer ma200Weight,
            Integer rsiWeight,
            Integer cmoWeight,
            Integer cmfWeight,
            String name,
            String description,
            String promptVersion,
            Double temperature) {
        this(id, numDatePoint, ma5Weight, ma10Weight, ma20Weight, ma50Weight, ma200Weight,
                rsiWeight, cmoWeight, cmfWeight, name, description, promptVersion, temperature, null);
    }

    public TuningParams(
            long id,
            Integer numDatePoint,
            Integer ma5Weight,
            Integer ma10Weight,
            Integer ma20Weight,
            Integer ma50Weight,
            Integer ma200Weight,
            Integer rsiWeight,
            Integer cmoWeight,
            Integer cmfWeight,
            String name,
            String description,
            String promptVersion) {
        this(id, numDatePoint, ma5Weight, ma10Weight, ma20Weight, ma50Weight, ma200Weight,
                rsiWeight, cmoWeight, cmfWeight, name, description, promptVersion, null, null);
    }

    public TuningParams(
            long id,
            Integer numDatePoint,
            Integer ma5Weight,
            Integer ma10Weight,
            Integer ma20Weight,
            Integer ma50Weight,
            Integer ma200Weight,
            Integer rsiWeight,
            Integer cmoWeight,
            Integer cmfWeight,
            String name,
            String description) {
        this(id, numDatePoint, ma5Weight, ma10Weight, ma20Weight, ma50Weight, ma200Weight,
                rsiWeight, cmoWeight, cmfWeight, name, description, null, null, null);
    }

    /** Prompt text version for Gemini ({@link GeminiPrompts}); {@code v1} when unset. */
    public String promptVersionOrDefault() {
        return promptVersion == null || promptVersion.isBlank()
                ? GeminiPrompts.DEFAULT_VERSION
                : promptVersion.trim();
    }

    /**
     * Gemini sampling temperature; null leaves the model default (param 1 keeps it null). Google
     * recommends the default 1.0 for Gemini 3 models; {@link #seed()} is the repeatability lever.
     */
    public Float temperatureOrNull() {
        return temperature == null ? null : temperature.floatValue();
    }

    /**
     * Soft guidance for Gemini: relative priority of each indicator in the market package
     * (keys match field names on bars / latest summary).
     */
    public static final String WEIGHT_MEANING =
            "Relative priority for decision-making: 0 = ignore that indicator, higher values = "
                    + "emphasize more in INDICATOR ANALYSIS and when choosing action. Weights are not "
                    + "multipliers of prices; do not invent missing series. OHLCV price structure still matters.";

    /**
     * Bars to feed Gemini. Falls back to {@link StockSummaryBuilder#DEFAULT_LOOKBACK_BARS}
     * when {@code num_date_point} is missing or invalid.
     */
    public int lookbackBars() {
        if (numDatePoint == null || numDatePoint < 1) {
            return StockSummaryBuilder.DEFAULT_LOOKBACK_BARS;
        }
        return numDatePoint;
    }

    /**
     * Weights keyed by payload field names ({@code ma5}, {@code rsi14}, {@code chandeMmt}, …).
     * Null DB weights default to 1.
     */
    public Map<String, Integer> indicatorWeights() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("ma5", weightOrDefault(ma5Weight));
        weights.put("ma10", weightOrDefault(ma10Weight));
        weights.put("ma20", weightOrDefault(ma20Weight));
        weights.put("ma50", weightOrDefault(ma50Weight));
        weights.put("ma200", weightOrDefault(ma200Weight));
        weights.put("rsi14", weightOrDefault(rsiWeight));
        weights.put("chandeMmt", weightOrDefault(cmoWeight));
        weights.put("chalkinMF", weightOrDefault(cmfWeight));
        return weights;
    }

    private static int weightOrDefault(Integer weight) {
        return weight == null ? 1 : weight;
    }
}
