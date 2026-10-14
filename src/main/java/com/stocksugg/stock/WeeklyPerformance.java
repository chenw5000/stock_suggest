package com.stocksugg.stock;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.db.StrategyOptimizeRepository;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Weekly walk-forward check: for each watch-list ticker, take the best strategy found on the
 * 3-month training window and apply it to the following 3-month test window ending on the week's
 * last trading day, then compare against Buy &amp; Hold on the same test window.
 */
public final class WeeklyPerformance {

    public static final double STARTING_CASH = 10_000.0;
    public static final int TOP_N = 10;
    /** Minimum trading days with a suggestion label in each window. */
    public static final int MIN_LABELED_DAYS = 20;
    /** Minimum share of trading days in each window that carry a suggestion label. */
    public static final double MIN_LABEL_COVERAGE = 0.8;
    /** A window whose first stored trading day is later than this after its start is incomplete. */
    public static final int MAX_START_GAP_DAYS = 7;

    /** Training window ends the day before the test window starts; both are inclusive. */
    public record Windows(
            LocalDate weekEnding,
            LocalDate asOf,
            LocalDate trainFrom,
            LocalDate trainTo,
            LocalDate testFrom,
            LocalDate testTo) {}

    public record Evaluation(PerformanceWeek week, List<PerformanceTickerResult> rows) {}

    @FunctionalInterface
    public interface Progress {
        void onTicker(int index, int total, String ticker);
    }

    private WeeklyPerformance() {}

    /** Friday of the week containing {@code date}; Saturday and Sunday map to the Friday before. */
    public static LocalDate weekEnding(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return date.with(TemporalAdjusters.previous(DayOfWeek.FRIDAY));
        }
        return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
    }

    /** Friday of the most recent week whose Friday session is complete. */
    public static LocalDate latestCompleteWeekEnding(LocalDate lastCompleteSession) {
        LocalDate friday = weekEnding(lastCompleteSession);
        return lastCompleteSession.isBefore(friday) ? friday.minusWeeks(1) : friday;
    }

    public static Windows windowsFor(LocalDate weekEnding, LocalDate asOf) {
        LocalDate trainTo = asOf.minusMonths(3);
        return new Windows(
                weekEnding,
                asOf,
                asOf.minusMonths(6).plusDays(1),
                trainTo,
                trainTo.plusDays(1),
                asOf);
    }

    /** Last stored trading day (any ticker) in the Monday..Friday week ending {@code weekEnding}. */
    public static Optional<LocalDate> anchorFor(StockRepository stocks, LocalDate weekEnding)
            throws SQLException {
        return stocks.findLatestDateBetween(weekEnding.minusDays(4), weekEnding);
    }

    public static Evaluation evaluate(
            Database db,
            LocalDate weekEnding,
            long paramId,
            List<String> tickers,
            boolean recompute,
            String triggerSource,
            Progress progress) throws SQLException {
        long startedAt = System.currentTimeMillis();
        StockRepository stocks = new StockRepository(db);
        StrategyOptimizeRepository optimize = new StrategyOptimizeRepository(db);
        LocalDate asOf = anchorFor(stocks, weekEnding).orElseThrow(() -> new IllegalArgumentException(
                "No trading data stored for the week ending " + weekEnding));
        Windows windows = windowsFor(weekEnding, asOf);

        List<PerformanceTickerResult> rows = new ArrayList<>(tickers.size());
        for (int i = 0; i < tickers.size(); i++) {
            String ticker = tickers.get(i);
            if (progress != null) {
                progress.onTicker(i + 1, tickers.size(), ticker);
            }
            try {
                rows.add(evaluateTicker(stocks, optimize, ticker, windows, paramId, recompute));
            } catch (SQLException e) {
                throw e;
            } catch (RuntimeException e) {
                rows.add(PerformanceTickerResult.skipped(ticker, "error: " + e.getMessage()));
            }
        }

        int evaluated = 0;
        int beat = 0;
        double strategyTotal = 0;
        double buyHoldTotal = 0;
        for (PerformanceTickerResult row : rows) {
            if (!row.ok()) {
                continue;
            }
            evaluated++;
            if (Boolean.TRUE.equals(row.beat())) {
                beat++;
            }
            strategyTotal += row.strategyEquity();
            buyHoldTotal += row.buyHoldEquity();
        }

        PerformanceWeek week = new PerformanceWeek(
                null,
                weekEnding,
                asOf,
                paramId,
                windows.trainFrom(),
                windows.trainTo(),
                windows.testFrom(),
                windows.testTo(),
                triggerSource,
                tickers.size(),
                evaluated,
                tickers.size() - evaluated,
                beat,
                evaluated - beat,
                evaluated * STARTING_CASH,
                strategyTotal,
                buyHoldTotal,
                System.currentTimeMillis() - startedAt,
                null);
        return new Evaluation(week, rows);
    }

    static PerformanceTickerResult evaluateTicker(
            StockRepository stocks,
            StrategyOptimizeRepository optimize,
            String ticker,
            Windows windows,
            long paramId,
            boolean recompute) throws SQLException {
        List<BacktestDay> trainDays =
                stocks.findBacktestDays(ticker, windows.trainFrom(), windows.trainTo(), paramId);
        String problem = coverageProblem(trainDays, windows.trainFrom(), "training");
        if (problem != null) {
            return PerformanceTickerResult.skipped(ticker, problem);
        }
        List<BacktestDay> testDays =
                stocks.findBacktestDays(ticker, windows.testFrom(), windows.testTo(), paramId);
        problem = coverageProblem(testDays, windows.testFrom(), "test");
        if (problem != null) {
            return PerformanceTickerResult.skipped(ticker, problem);
        }

        Optional<BestStrategy> found = bestStrategy(
                optimize, ticker, paramId, windows.trainFrom(), windows.trainTo(), trainDays, recompute);
        if (found.isEmpty()) {
            return PerformanceTickerResult.skipped(
                    ticker, "no strategy candidates for the training window");
        }
        StrategyOptimize best = found.get().row();
        boolean cached = found.get().cached();

        BacktestStrategy strategy = best.toStrategy();
        SuggestionBacktester.Result result = SuggestionBacktester.run(STARTING_CASH, strategy, testDays);
        SuggestionBacktester.Result buyAndHold = SuggestionBacktester.buyAndHold(STARTING_CASH, testDays);
        return new PerformanceTickerResult(
                ticker,
                PerformanceTickerResult.STATUS_OK,
                null,
                strategy,
                cached,
                best.returnPct(),
                best.buyHoldReturnPct(),
                testDays.size(),
                result.endingEquity(),
                result.returnPct(),
                result.buyCount(),
                result.sellCount(),
                buyAndHold.endingEquity(),
                buyAndHold.returnPct(),
                result.endingEquity() > buyAndHold.endingEquity() + 0.005);
    }

    /** Best strategy for a training window; {@code cached} when reused from {@code strategy_optimize}. */
    public record BestStrategy(StrategyOptimize row, boolean cached) {}

    /**
     * Saved rank-1 strategy for exactly this training window (unless {@code recompute}); otherwise
     * runs the grid search on {@code trainDays} and saves the winner. Empty when the search has no
     * candidates.
     */
    public static Optional<BestStrategy> bestStrategy(
            StrategyOptimizeRepository optimize,
            String ticker,
            long paramId,
            LocalDate trainFrom,
            LocalDate trainTo,
            List<BacktestDay> trainDays,
            boolean recompute) throws SQLException {
        if (!recompute) {
            Optional<StrategyOptimize> saved =
                    optimize.findExactBest(ticker, paramId, trainFrom, trainTo, STARTING_CASH);
            if (saved.isPresent()) {
                return Optional.of(new BestStrategy(saved.get(), true));
            }
        }
        SuggestionStrategyOptimizer.Report report =
                SuggestionStrategyOptimizer.search(STARTING_CASH, trainDays, TOP_N);
        if (report.top().isEmpty()) {
            return Optional.empty();
        }
        SuggestionStrategyOptimizer.Candidate top = report.top().getFirst();
        StrategyOptimize row = StrategyOptimize.fromSearch(
                ticker,
                paramId,
                trainFrom,
                trainTo,
                STARTING_CASH,
                top.strategy(),
                top.result(),
                report.buyAndHold(),
                1);
        optimize.upsert(row);
        return Optional.of(new BestStrategy(row, false));
    }

    /** Reason the window cannot be evaluated, or {@code null} when it has enough labeled data. */
    static String coverageProblem(List<BacktestDay> days, LocalDate windowStart, String window) {
        if (days.isEmpty()) {
            return "insufficient data: no trading days in the " + window + " window";
        }
        LocalDate first = days.getFirst().date();
        if (first.isAfter(windowStart.plusDays(MAX_START_GAP_DAYS))) {
            return "insufficient data: " + window + " window data starts on " + first;
        }
        long labeled = days.stream()
                .filter(d -> d.suggestedAction() != null && !d.suggestedAction().isBlank())
                .count();
        if (labeled < MIN_LABELED_DAYS || labeled < days.size() * MIN_LABEL_COVERAGE) {
            return "insufficient data: " + window + " window has suggestions on "
                    + labeled + " of " + days.size() + " trading days";
        }
        return null;
    }
}
