package com.stocksugg.stock;

/**
 * One ticker's row in a weekly performance snapshot ({@code performance_week_ticker}).
 * Skipped rows only carry {@code ticker}, {@code status} and {@code skipReason}.
 *
 * @param strategyCached true when the training-window best strategy was reused from
 *                       {@code strategy_optimize} instead of searched again
 * @param beat           strategy ending equity strictly greater than Buy &amp; Hold on the test window
 */
public record PerformanceTickerResult(
        String ticker,
        String status,
        String skipReason,
        BacktestStrategy strategy,
        Boolean strategyCached,
        Double trainReturnPct,
        Double trainBuyHoldReturnPct,
        Integer testTradingDays,
        Double strategyEquity,
        Double strategyReturnPct,
        Integer buyCount,
        Integer sellCount,
        Double buyHoldEquity,
        Double buyHoldReturnPct,
        Boolean beat
) {
    public static final String STATUS_OK = "OK";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static PerformanceTickerResult skipped(String ticker, String reason) {
        return new PerformanceTickerResult(
                ticker, STATUS_SKIPPED, reason,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public boolean ok() {
        return STATUS_OK.equals(status);
    }
}
