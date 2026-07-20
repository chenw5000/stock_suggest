package com.stocksugg.stock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Long-only backtest driven by Gemini labels, optionally sized in equal cash parts (lots).
 */
public final class SuggestionBacktester {

    public record Trade(
            BacktestDay day,
            String event,
            int sharesDelta,
            double price,
            double cashAfter,
            int sharesAfter,
            double equityAfter
    ) {}

    public record Result(
            double startingCash,
            double endingCash,
            int endingShares,
            double endingClose,
            double endingEquity,
            double returnPct,
            int buyCount,
            int sellCount,
            int skippedBuys,
            List<Trade> trades
    ) {}

    private SuggestionBacktester() {}

    /** All-in / all-out (AVOID sells all). */
    public static Result run(double startingCash, List<BacktestDay> daysOldestFirst) {
        return run(startingCash, BacktestStrategy.allIn(), daysOldestFirst);
    }

    /** Equal-part lots: BUY / SELL / AVOID each move one part. */
    public static Result runParts(double startingCash, int parts, List<BacktestDay> daysOldestFirst) {
        return run(startingCash, BacktestStrategy.equalParts(parts), daysOldestFirst);
    }

    public static Result run(
            double startingCash,
            BacktestStrategy strategy,
            List<BacktestDay> daysOldestFirst) {
        if (startingCash <= 0) {
            throw new IllegalArgumentException("startingCash must be > 0");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy is required");
        }
        if (strategy.parts() < 1) {
            throw new IllegalArgumentException("parts must be >= 1");
        }
        if (daysOldestFirst == null || daysOldestFirst.isEmpty()) {
            throw new IllegalArgumentException("at least one trading day is required");
        }

        double partSize = startingCash / strategy.parts();
        double cash = startingCash;
        Deque<Integer> lots = new ArrayDeque<>();
        int buyCount = 0;
        int sellCount = 0;
        int skippedBuys = 0;
        List<Trade> trades = new ArrayList<>();
        float lastClose = daysOldestFirst.getFirst().close();

        for (BacktestDay day : daysOldestFirst) {
            if (day.close() <= 0f) {
                continue;
            }
            lastClose = day.close();
            BacktestStrategy.TradeIntent intent = strategy.intentFor(day.suggestedAction());
            if (intent == BacktestStrategy.TradeIntent.NONE) {
                continue;
            }
            if (!strategy.passesConfidence(day.suggestedAction(), day.confidence())) {
                trades.add(trade(day, "SKIP_LOW_CONFIDENCE", 0, cash, totalShares(lots)));
                continue;
            }

            switch (intent) {
                case BUY_PART, BUY_ALL -> {
                    if (cash < day.close()) {
                        skippedBuys++;
                        trades.add(trade(day, "SKIP_BUY_NO_CASH", 0, cash, totalShares(lots)));
                        break;
                    }
                    double budget = intent == BacktestStrategy.TradeIntent.BUY_ALL
                            ? cash
                            : Math.min(partSize, cash);
                    // Classic all-in: only buy when flat
                    if (intent == BacktestStrategy.TradeIntent.BUY_ALL
                            && strategy.parts() == 1
                            && !lots.isEmpty()) {
                        skippedBuys++;
                        trades.add(trade(day, "SKIP_BUY_ALREADY_LONG", 0, cash, totalShares(lots)));
                        break;
                    }
                    int toBuy = (int) Math.floor(budget / day.close());
                    if (toBuy <= 0) {
                        skippedBuys++;
                        trades.add(trade(day, "SKIP_BUY_PART_TOO_SMALL", 0, cash, totalShares(lots)));
                    } else {
                        cash -= toBuy * (double) day.close();
                        lots.addLast(toBuy);
                        buyCount++;
                        String event = intent == BacktestStrategy.TradeIntent.BUY_ALL ? "BUY_ALL" : "BUY_PART";
                        trades.add(trade(day, event, toBuy, cash, totalShares(lots)));
                    }
                }
                case SELL_PART, SELL_ALL -> {
                    if (lots.isEmpty()) {
                        String action = day.suggestedAction() == null
                                ? ""
                                : day.suggestedAction().trim().toUpperCase(Locale.ROOT);
                        if ("SELL".equals(action)) {
                            trades.add(trade(day, "SKIP_SELL_NO_SHARES", 0, cash, 0));
                        }
                        break;
                    }
                    int sold;
                    String event;
                    if (intent == BacktestStrategy.TradeIntent.SELL_ALL) {
                        sold = totalShares(lots);
                        lots.clear();
                        event = "SELL_ALL";
                    } else {
                        sold = lots.removeFirst();
                        event = "SELL_PART";
                    }
                    cash += sold * (double) day.close();
                    sellCount++;
                    trades.add(trade(day, event, -sold, cash, totalShares(lots)));
                }
                case NONE -> {
                    // unreachable
                }
            }
        }

        double endingEquity = cash + totalShares(lots) * (double) lastClose;
        double returnPct = ((endingEquity - startingCash) / startingCash) * 100.0;
        return new Result(
                startingCash,
                cash,
                totalShares(lots),
                lastClose,
                endingEquity,
                returnPct,
                buyCount,
                sellCount,
                skippedBuys,
                List.copyOf(trades));
    }

    /** Buy-and-hold: spend all cash on day 1, hold to the end. */
    public static Result buyAndHold(double startingCash, List<BacktestDay> daysOldestFirst) {
        if (daysOldestFirst == null || daysOldestFirst.isEmpty()) {
            throw new IllegalArgumentException("at least one trading day is required");
        }
        BacktestDay first = daysOldestFirst.getFirst();
        BacktestDay last = daysOldestFirst.getLast();
        int shares = (int) Math.floor(startingCash / first.close());
        double cash = startingCash - shares * (double) first.close();
        double equity = cash + shares * (double) last.close();
        double returnPct = ((equity - startingCash) / startingCash) * 100.0;
        Trade buy = new Trade(first, "BUY_HOLD", shares, first.close(), cash, shares,
                cash + shares * (double) first.close());
        return new Result(startingCash, cash, shares, last.close(), equity, returnPct,
                1, 0, 0, List.of(buy));
    }

    private static int totalShares(Deque<Integer> lots) {
        int total = 0;
        for (int lot : lots) {
            total += lot;
        }
        return total;
    }

    private static Trade trade(BacktestDay day, String event, int sharesDelta, double cash, int shares) {
        double equity = cash + shares * (double) day.close();
        return new Trade(day, event, sharesDelta, day.close(), cash, shares, equity);
    }
}
