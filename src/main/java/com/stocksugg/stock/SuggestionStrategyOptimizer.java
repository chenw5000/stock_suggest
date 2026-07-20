package com.stocksugg.stock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Grid-searches {@link BacktestStrategy} parameters for higher return on a fixed day series.
 */
public final class SuggestionStrategyOptimizer {

    public record Candidate(
            BacktestStrategy strategy,
            SuggestionBacktester.Result result
    ) {}

    public record Report(
            SuggestionBacktester.Result buyAndHold,
            SuggestionBacktester.Result baselineAllIn,
            SuggestionBacktester.Result baselineParts4,
            List<Candidate> top
    ) {}

    private SuggestionStrategyOptimizer() {}

    public static Report search(double startingCash, List<BacktestDay> days, int topN) {
        if (topN < 1) {
            throw new IllegalArgumentException("topN must be >= 1");
        }

        int[] partsOptions = {1, 2, 3, 4, 5, 6, 8, 10};
        double[] buyConfOptions = {0.0, 0.40, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80};
        double[] sellConfOptions = {0.0, 0.40, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80};

        BacktestStrategy.TradeIntent[] buyIntents = {
                BacktestStrategy.TradeIntent.BUY_PART,
                BacktestStrategy.TradeIntent.BUY_ALL
        };
        BacktestStrategy.TradeIntent[] sellIntents = {
                BacktestStrategy.TradeIntent.NONE,
                BacktestStrategy.TradeIntent.SELL_PART,
                BacktestStrategy.TradeIntent.SELL_ALL
        };
        BacktestStrategy.TradeIntent[] avoidIntents = {
                BacktestStrategy.TradeIntent.NONE,
                BacktestStrategy.TradeIntent.SELL_PART,
                BacktestStrategy.TradeIntent.SELL_ALL
        };

        List<Candidate> all = new ArrayList<>();
        for (int parts : partsOptions) {
            for (double buyConf : buyConfOptions) {
                for (double sellConf : sellConfOptions) {
                    for (BacktestStrategy.TradeIntent onBuy : buyIntents) {
                        for (BacktestStrategy.TradeIntent onSell : sellIntents) {
                            for (BacktestStrategy.TradeIntent onAvoid : avoidIntents) {
                                // Skip useless combos: never buy
                                if (onBuy == BacktestStrategy.TradeIntent.NONE) {
                                    continue;
                                }
                                // parts=1 + BUY_PART is same sizing as BUY_ALL when flat-only
                                // still useful with multi-buy allowed via BUY_PART on parts=1
                                BacktestStrategy strategy = new BacktestStrategy(
                                        parts,
                                        buyConf,
                                        sellConf,
                                        onBuy,
                                        onSell,
                                        BacktestStrategy.TradeIntent.NONE,
                                        onAvoid);
                                SuggestionBacktester.Result result =
                                        SuggestionBacktester.run(startingCash, strategy, days);
                                all.add(new Candidate(strategy, result));
                            }
                        }
                    }
                }
            }
        }

        all.sort(Comparator
                .comparingDouble((Candidate c) -> c.result().endingEquity())
                .reversed()
                .thenComparingInt(c -> c.result().trades().size()));

        List<Candidate> top = all.subList(0, Math.min(topN, all.size()));
        return new Report(
                SuggestionBacktester.buyAndHold(startingCash, days),
                SuggestionBacktester.run(startingCash, days),
                SuggestionBacktester.runParts(startingCash, 4, days),
                List.copyOf(top));
    }
}
