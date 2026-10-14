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

        Segment segment = simulate(
                Position.ofCash(startingCash), startingCash / strategy.parts(), strategy, daysOldestFirst);
        Position end = segment.end();
        double endingEquity = end.equity(segment.lastClose());
        double returnPct = ((endingEquity - startingCash) / startingCash) * 100.0;
        return new Result(
                startingCash,
                end.cash(),
                end.shares(),
                segment.lastClose(),
                endingEquity,
                returnPct,
                segment.buyCount(),
                segment.sellCount(),
                segment.skippedBuys(),
                segment.trades());
    }

    /** Cash plus open lots (oldest first); carried from one simulated period into the next. */
    public record Position(double cash, List<Integer> lots) {
        public Position {
            lots = List.copyOf(lots);
        }

        public static Position ofCash(double cash) {
            return new Position(cash, List.of());
        }

        public int shares() {
            int total = 0;
            for (int lot : lots) {
                total += lot;
            }
            return total;
        }

        public double equity(double price) {
            return cash + shares() * price;
        }
    }

    /**
     * One simulated stretch of days. {@code dailyEquity} / {@code dailyShares} are aligned with the
     * input days (end-of-day, after that day's trade).
     */
    public record Segment(
            Position end,
            double lastClose,
            int buyCount,
            int sellCount,
            int skippedBuys,
            List<Trade> trades,
            double[] dailyEquity,
            int[] dailyShares) {}

    /**
     * Runs {@code strategy} over {@code days} starting from {@code start} instead of all cash.
     * {@code partSize} is the cash budget of one BUY_PART lot.
     */
    public static Segment simulate(
            Position start,
            double partSize,
            BacktestStrategy strategy,
            List<BacktestDay> daysOldestFirst) {
        if (start == null) {
            throw new IllegalArgumentException("start position is required");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy is required");
        }
        if (daysOldestFirst == null || daysOldestFirst.isEmpty()) {
            throw new IllegalArgumentException("at least one trading day is required");
        }

        Book book = new Book(start, partSize);
        float lastClose = daysOldestFirst.getFirst().close();
        double[] dailyEquity = new double[daysOldestFirst.size()];
        int[] dailyShares = new int[daysOldestFirst.size()];

        for (int i = 0; i < daysOldestFirst.size(); i++) {
            BacktestDay day = daysOldestFirst.get(i);
            if (day.close() > 0f) {
                lastClose = day.close();
                book.apply(strategy, day);
            }
            dailyShares[i] = totalShares(book.lots);
            dailyEquity[i] = book.cash + dailyShares[i] * (double) Math.max(lastClose, 0f);
        }

        return new Segment(
                new Position(book.cash, List.copyOf(book.lots)),
                lastClose,
                book.buyCount,
                book.sellCount,
                book.skippedBuys,
                List.copyOf(book.trades),
                dailyEquity,
                dailyShares);
    }

    /**
     * Re-splits held shares into lots sized for a strategy with {@code parts} lots, so a position
     * carried from a period with a different {@code parts} can be sold part by part.
     */
    public static Position relot(Position position, double price, int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be >= 1");
        }
        int shares = position.shares();
        if (shares == 0) {
            return Position.ofCash(position.cash());
        }
        double partSize = position.equity(price) / parts;
        int lotCount = partSize <= 0
                ? 1
                : (int) Math.max(1, Math.min(parts, Math.round(shares * price / partSize)));
        lotCount = Math.min(lotCount, shares);
        List<Integer> lots = new ArrayList<>(lotCount);
        int base = shares / lotCount;
        int remainder = shares % lotCount;
        for (int i = 0; i < lotCount; i++) {
            lots.add(base + (i < remainder ? 1 : 0));
        }
        return new Position(position.cash(), lots);
    }

    /** Mutable state for one simulation. */
    private static final class Book {
        private final double partSize;
        private double cash;
        private final Deque<Integer> lots;
        private int buyCount;
        private int sellCount;
        private int skippedBuys;
        private final List<Trade> trades = new ArrayList<>();

        private Book(Position start, double partSize) {
            this.cash = start.cash();
            this.lots = new ArrayDeque<>(start.lots());
            this.partSize = partSize;
        }

        private void apply(BacktestStrategy strategy, BacktestDay day) {
            BacktestStrategy.TradeIntent intent = strategy.intentFor(day);
            if (intent == BacktestStrategy.TradeIntent.NONE) {
                return;
            }
            if (!strategy.passesConfidence(intent, day.confidence())) {
                trades.add(trade(day, "SKIP_LOW_CONFIDENCE", 0, cash, totalShares(lots)));
                return;
            }

            // Prefer plain conditionals (not multi-case switch with blocks) so javac does not
            // emit synthetic SuggestionBacktester$1 — exec:java's classloader often fails on it
            // after an incomplete incremental compile.
            if (intent == BacktestStrategy.TradeIntent.BUY_PART
                    || intent == BacktestStrategy.TradeIntent.BUY_ALL) {
                if (cash < day.close()) {
                    skippedBuys++;
                    trades.add(trade(day, "SKIP_BUY_NO_CASH", 0, cash, totalShares(lots)));
                    return;
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
                    return;
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
            } else if (intent == BacktestStrategy.TradeIntent.SELL_PART
                    || intent == BacktestStrategy.TradeIntent.SELL_ALL) {
                if (lots.isEmpty()) {
                    String action = day.suggestedAction() == null
                            ? ""
                            : day.suggestedAction().trim().toUpperCase(Locale.ROOT);
                    if ("SELL".equals(action)) {
                        trades.add(trade(day, "SKIP_SELL_NO_SHARES", 0, cash, 0));
                    }
                    return;
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
        }
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
