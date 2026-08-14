package com.stocksugg.stock;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row from {@code strategy_optimize}: a strategy-search result for a ticker window.
 */
public record StrategyOptimize(
        Long id,
        String ticker,
        long paramId,
        LocalDate fromDate,
        LocalDate toDate,
        double startingCash,
        int parts,
        double minBuyConfidence,
        double minSellConfidence,
        String onBuy,
        String onSell,
        String onHold,
        String onAvoid,
        double endingEquity,
        Double endingCash,
        Integer endingShares,
        Double lastClose,
        double returnPct,
        Integer buyCount,
        Integer sellCount,
        Integer skippedBuys,
        Double buyHoldEquity,
        Double buyHoldReturnPct,
        int rank,
        Instant computedAt
) {
    /** Builds a row from optimizer outputs (id / computedAt filled on insert by the DB). */
    public static StrategyOptimize fromSearch(
            String ticker,
            long paramId,
            LocalDate fromDate,
            LocalDate toDate,
            double startingCash,
            BacktestStrategy strategy,
            SuggestionBacktester.Result result,
            SuggestionBacktester.Result buyAndHold,
            int rank) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy is required");
        }
        if (result == null) {
            throw new IllegalArgumentException("result is required");
        }
        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("fromDate and toDate are required");
        }
        if (fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("fromDate must be on or before toDate");
        }
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be >= 1");
        }

        return new StrategyOptimize(
                null,
                ticker.trim().toUpperCase(java.util.Locale.ROOT),
                paramId,
                fromDate,
                toDate,
                startingCash,
                strategy.parts(),
                strategy.minBuyConfidence(),
                strategy.minSellConfidence(),
                strategy.onBuy().name(),
                strategy.onSell().name(),
                strategy.onHold().name(),
                strategy.onAvoid().name(),
                result.endingEquity(),
                result.endingCash(),
                result.endingShares(),
                result.endingClose(),
                result.returnPct(),
                result.buyCount(),
                result.sellCount(),
                result.skippedBuys(),
                buyAndHold == null ? null : buyAndHold.endingEquity(),
                buyAndHold == null ? null : buyAndHold.returnPct(),
                rank,
                null);
    }

    /** Reconstructs the strategy params stored on this row. */
    public BacktestStrategy toStrategy() {
        return new BacktestStrategy(
                parts,
                minBuyConfidence,
                minSellConfidence,
                BacktestStrategy.TradeIntent.valueOf(onBuy),
                BacktestStrategy.TradeIntent.valueOf(onSell),
                BacktestStrategy.TradeIntent.valueOf(onHold),
                BacktestStrategy.TradeIntent.valueOf(onAvoid));
    }
}
