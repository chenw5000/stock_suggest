package com.stocksugg.stock;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Summary of one weekly performance snapshot ({@code performance_week}): the best strategy from
 * the training window applied to the following test window, for every watch-list ticker.
 *
 * @param weekEnding  Friday of the evaluated week (unique per param)
 * @param asOfDate    last trading day of that week (normally {@code weekEnding})
 * @param invested    {@code evaluated} x starting cash
 */
public record PerformanceWeek(
        Long id,
        LocalDate weekEnding,
        LocalDate asOfDate,
        long paramId,
        LocalDate trainFrom,
        LocalDate trainTo,
        LocalDate testFrom,
        LocalDate testTo,
        String triggerSource,
        int tickersTotal,
        int evaluated,
        int skipped,
        int beatCount,
        int notBeatCount,
        double invested,
        double strategyTotal,
        double buyHoldTotal,
        Long durationMs,
        Instant computedAt
) {
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_BACKFILL = "BACKFILL";

    public double strategyReturnPct() {
        return invested <= 0 ? 0.0 : (strategyTotal / invested - 1.0) * 100.0;
    }

    public double buyHoldReturnPct() {
        return invested <= 0 ? 0.0 : (buyHoldTotal / invested - 1.0) * 100.0;
    }
}
