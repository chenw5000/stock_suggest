package com.stocksugg.stock;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.db.StrategyOptimizeRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Walk-forward simulation with carried positions: one continuous account per ticker over the whole
 * test span. At the start of each period (quarterly or weekly) the strategy is re-chosen as the best
 * one on the trailing {@link #TRAIN_MONTHS} months, and the cash and shares held at that moment carry
 * into the period. Buy &amp; Hold and a fixed untuned strategy run over the same span for comparison.
 */
public final class WalkForward {

    public enum Cadence { QUARTERLY, WEEKLY }

    public static final int TRAIN_MONTHS = 3;
    public static final double STARTING_CASH = 10_000.0;
    public static final String FIXED_STRATEGY_LABEL = "All-in: BUY buys all; SELL / AVOID sell all";

    public static final String STATUS_OK = "OK";
    public static final String STATUS_SKIPPED = "SKIPPED";

    /** Period uses a strategy found on its own training window. */
    public static final String PERIOD_NEW = "NEW";
    /** Training window lacked data; the previous period's strategy keeps running. */
    public static final String PERIOD_KEPT = "KEPT_PREVIOUS";
    /** No strategy yet; the position is left unchanged. */
    public static final String PERIOD_IDLE = "IDLE";

    private static final BacktestStrategy IDLE = new BacktestStrategy(
            1, 0, 0,
            BacktestStrategy.TradeIntent.NONE,
            BacktestStrategy.TradeIntent.NONE,
            BacktestStrategy.TradeIntent.NONE,
            BacktestStrategy.TradeIntent.NONE,
            BacktestStrategy.TradeIntent.NONE);

    public record Period(LocalDate trainFrom, LocalDate trainTo, LocalDate testFrom, LocalDate testTo) {}

    public record Metrics(
            double endingEquity,
            double returnPct,
            double maxDrawdownPct,
            double timeInMarketPct,
            int trades) {}

    public record PeriodResult(
            LocalDate testFrom,
            LocalDate testTo,
            LocalDate trainFrom,
            LocalDate trainTo,
            String status,
            String note,
            Integer parts,
            Double minBuyConfidence,
            Double minSellConfidence,
            String onBuy,
            String onSell,
            String onAvoidHigh,
            String onAvoidLow,
            Boolean cached,
            Double trainReturnPct,
            double startEquity,
            double endEquity,
            int buys,
            int sells) {}

    public record TickerResult(
            String ticker,
            String status,
            String skipReason,
            Metrics buyHold,
            Metrics walkForward,
            Metrics fixed,
            Boolean beat,
            Boolean fixedBeat,
            List<PeriodResult> periods,
            List<LocalDate> dates,
            List<Double> buyHoldEquity,
            List<Double> walkForwardEquity,
            List<Double> fixedEquity) {

        static TickerResult skipped(String ticker, String reason) {
            return new TickerResult(ticker, STATUS_SKIPPED, reason,
                    null, null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public boolean ok() {
            return STATUS_OK.equals(status);
        }
    }

    public record Portfolio(
            List<LocalDate> dates,
            List<Double> buyHold,
            List<Double> walkForward,
            List<Double> fixed,
            double invested,
            Metrics buyHoldMetrics,
            Metrics walkForwardMetrics,
            Metrics fixedMetrics,
            int walkForwardBeatCount,
            int fixedBeatCount,
            int walkForwardBeatsFixedCount) {}

    public record Report(
            String cadence,
            LocalDate testFrom,
            LocalDate testTo,
            int trainMonths,
            long paramId,
            double cashPerTicker,
            String fixedStrategy,
            List<Period> periods,
            int tickersTotal,
            int included,
            Portfolio portfolio,
            List<TickerResult> tickers,
            long durationMs) {}

    @FunctionalInterface
    public interface Progress {
        void onTicker(int index, int total, String ticker);
    }

    private WalkForward() {}

    /**
     * Consecutive test periods covering [{@code testFrom}, {@code testTo}], each with its trailing
     * training window. A final stub shorter than half a period is folded into the previous period.
     */
    public static List<Period> periods(Cadence cadence, LocalDate testFrom, LocalDate testTo) {
        if (testFrom.isAfter(testTo)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        long minStubDays = cadence == Cadence.QUARTERLY ? 45 : 4;
        List<Period> periods = new ArrayList<>();
        LocalDate start = testFrom;
        while (!start.isAfter(testTo)) {
            LocalDate end = cadence == Cadence.QUARTERLY
                    ? start.plusMonths(3).minusDays(1)
                    : start.plusDays(6);
            if (end.isAfter(testTo)
                    || java.time.temporal.ChronoUnit.DAYS.between(end, testTo) < minStubDays) {
                end = testTo;
            }
            periods.add(new Period(start.minusMonths(TRAIN_MONTHS), start.minusDays(1), start, end));
            start = end.plusDays(1);
        }
        return periods;
    }

    /**
     * Default test span: starts nine months ago (so the first training window starts a year ago), but
     * not before {@link #TRAIN_MONTHS} months after the typical (median) ticker's first suggestion;
     * ends on the latest stored day.
     */
    public static LocalDate[] defaultSpan(StockRepository stocks, long paramId, LocalDate today)
            throws SQLException {
        LocalDate to = stocks.findAdjacentTradingDate(today.plusDays(1), -1).orElse(today);
        LocalDate from = today.minusMonths(9);
        List<LocalDate> firstLabeled = stocks.findFirstLabeledDates(paramId);
        if (!firstLabeled.isEmpty()) {
            LocalDate earliest = firstLabeled.get(firstLabeled.size() / 2).plusMonths(TRAIN_MONTHS);
            if (earliest.isAfter(from)) {
                from = earliest;
            }
        }
        if (from.isAfter(to)) {
            from = to;
        }
        return new LocalDate[] {from, to};
    }

    public static Report run(
            Database db,
            List<String> tickers,
            Cadence cadence,
            LocalDate testFrom,
            LocalDate testTo,
            long paramId,
            boolean recompute,
            Progress progress) throws SQLException {
        long startedAt = System.currentTimeMillis();
        List<Period> periods = periods(cadence, testFrom, testTo);
        StockRepository stocks = new StockRepository(db);
        StrategyOptimizeRepository optimize = new StrategyOptimizeRepository(db);

        List<TickerResult> results = new ArrayList<>(tickers.size());
        for (int i = 0; i < tickers.size(); i++) {
            String ticker = tickers.get(i);
            if (progress != null) {
                progress.onTicker(i + 1, tickers.size(), ticker);
            }
            try {
                results.add(runTicker(stocks, optimize, ticker, periods, testFrom, testTo, paramId, recompute));
            } catch (SQLException e) {
                throw e;
            } catch (RuntimeException e) {
                results.add(TickerResult.skipped(ticker, "error: " + e.getMessage()));
            }
        }

        Portfolio portfolio = portfolio(results);
        int included = (int) results.stream().filter(TickerResult::ok).count();
        return new Report(
                cadence.name(),
                testFrom,
                testTo,
                TRAIN_MONTHS,
                paramId,
                STARTING_CASH,
                FIXED_STRATEGY_LABEL,
                periods,
                tickers.size(),
                included,
                portfolio,
                results,
                System.currentTimeMillis() - startedAt);
    }

    static TickerResult runTicker(
            StockRepository stocks,
            StrategyOptimizeRepository optimize,
            String ticker,
            List<Period> periods,
            LocalDate testFrom,
            LocalDate testTo,
            long paramId,
            boolean recompute) throws SQLException {
        List<BacktestDay> allDays = stocks.findBacktestDays(ticker, testFrom, testTo, paramId);
        if (allDays.isEmpty()) {
            return TickerResult.skipped(ticker, "insufficient data: no trading days in the test span");
        }
        if (allDays.getFirst().date().isAfter(testFrom.plusDays(WeeklyPerformance.MAX_START_GAP_DAYS))) {
            return TickerResult.skipped(ticker,
                    "insufficient data: test span data starts on " + allDays.getFirst().date());
        }
        Period first = periods.getFirst();
        List<BacktestDay> firstTrain =
                stocks.findBacktestDays(ticker, first.trainFrom(), first.trainTo(), paramId);
        String firstProblem = WeeklyPerformance.coverageProblem(firstTrain, first.trainFrom(), "first training");
        if (firstProblem != null) {
            return TickerResult.skipped(ticker, firstProblem);
        }

        SuggestionBacktester.Position position = SuggestionBacktester.Position.ofCash(STARTING_CASH);
        double lastClose = allDays.getFirst().close();
        StrategyOptimize currentRow = null;
        double[] wfEquity = new double[allDays.size()];
        int[] wfShares = new int[allDays.size()];
        int filled = 0;
        int wfTrades = 0;
        List<PeriodResult> periodResults = new ArrayList<>(periods.size());

        for (Period period : periods) {
            List<BacktestDay> trainDays = period == first
                    ? firstTrain
                    : stocks.findBacktestDays(ticker, period.trainFrom(), period.trainTo(), paramId);
            String problem = WeeklyPerformance.coverageProblem(trainDays, period.trainFrom(), "training");
            Boolean cached = null;
            String note = problem;
            boolean fresh = false;
            if (problem == null) {
                Optional<WeeklyPerformance.BestStrategy> best = WeeklyPerformance.bestStrategy(
                        optimize, ticker, paramId, period.trainFrom(), period.trainTo(), trainDays, recompute);
                if (best.isPresent()) {
                    currentRow = best.get().row();
                    cached = best.get().cached();
                    fresh = true;
                } else {
                    note = "no strategy candidates for the training window";
                }
            }
            String status = fresh ? PERIOD_NEW : currentRow == null ? PERIOD_IDLE : PERIOD_KEPT;

            List<BacktestDay> testDays = slice(allDays, period.testFrom(), period.testTo());
            double startEquity = position.equity(lastClose);
            if (testDays.isEmpty()) {
                periodResults.add(periodResult(period, status, "no trading days in this period",
                        currentRow, cached, startEquity, startEquity, 0, 0));
                continue;
            }

            BacktestStrategy strategy = currentRow == null ? IDLE : currentRow.toStrategy();
            position = SuggestionBacktester.relot(position, lastClose, strategy.parts());
            SuggestionBacktester.Segment segment = SuggestionBacktester.simulate(
                    position, startEquity / strategy.parts(), strategy, testDays);
            position = segment.end();
            lastClose = segment.lastClose();
            System.arraycopy(segment.dailyEquity(), 0, wfEquity, filled, testDays.size());
            System.arraycopy(segment.dailyShares(), 0, wfShares, filled, testDays.size());
            filled += testDays.size();
            wfTrades += segment.buyCount() + segment.sellCount();
            periodResults.add(periodResult(period, status, note, currentRow, cached, startEquity,
                    segment.dailyEquity()[testDays.size() - 1], segment.buyCount(), segment.sellCount()));
        }

        double[] bhEquity = buyAndHoldEquity(allDays);
        int[] bhShares = new int[allDays.size()];
        java.util.Arrays.fill(bhShares, 1);
        SuggestionBacktester.Segment fixed = SuggestionBacktester.simulate(
                SuggestionBacktester.Position.ofCash(STARTING_CASH), STARTING_CASH,
                BacktestStrategy.allIn(), allDays);

        Metrics buyHold = metrics(bhEquity, bhShares, 1);
        Metrics walkForward = metrics(wfEquity, wfShares, wfTrades);
        Metrics fixedMetrics = metrics(fixed.dailyEquity(), fixed.dailyShares(),
                fixed.buyCount() + fixed.sellCount());

        return new TickerResult(
                ticker,
                STATUS_OK,
                null,
                buyHold,
                walkForward,
                fixedMetrics,
                walkForward.endingEquity() > buyHold.endingEquity() + 0.005,
                fixedMetrics.endingEquity() > buyHold.endingEquity() + 0.005,
                periodResults,
                allDays.stream().map(BacktestDay::date).toList(),
                rounded(bhEquity),
                rounded(wfEquity),
                rounded(fixed.dailyEquity()));
    }

    private static PeriodResult periodResult(
            Period period,
            String status,
            String note,
            StrategyOptimize row,
            Boolean cached,
            double startEquity,
            double endEquity,
            int buys,
            int sells) {
        return new PeriodResult(
                period.testFrom(),
                period.testTo(),
                period.trainFrom(),
                period.trainTo(),
                status,
                note,
                row == null ? null : row.parts(),
                row == null ? null : row.minBuyConfidence(),
                row == null ? null : row.minSellConfidence(),
                row == null ? null : row.onBuy(),
                row == null ? null : row.onSell(),
                row == null ? null : row.onAvoidHigh(),
                row == null ? null : row.onAvoidLow(),
                cached,
                row == null ? null : row.returnPct(),
                round2(startEquity),
                round2(endEquity),
                buys,
                sells);
    }

    private static List<BacktestDay> slice(List<BacktestDay> days, LocalDate from, LocalDate to) {
        return days.stream()
                .filter(d -> !d.date().isBefore(from) && !d.date().isAfter(to))
                .toList();
    }

    private static double[] buyAndHoldEquity(List<BacktestDay> days) {
        double firstClose = days.getFirst().close();
        int shares = firstClose > 0 ? (int) Math.floor(STARTING_CASH / firstClose) : 0;
        double cash = STARTING_CASH - shares * firstClose;
        double[] equity = new double[days.size()];
        double close = firstClose;
        for (int i = 0; i < days.size(); i++) {
            if (days.get(i).close() > 0f) {
                close = days.get(i).close();
            }
            equity[i] = cash + shares * close;
        }
        return equity;
    }

    static Metrics metrics(double[] equity, int[] shares, int trades) {
        double peak = STARTING_CASH;
        double maxDrawdown = 0;
        int inMarket = 0;
        for (int i = 0; i < equity.length; i++) {
            peak = Math.max(peak, equity[i]);
            if (peak > 0) {
                maxDrawdown = Math.max(maxDrawdown, (peak - equity[i]) / peak);
            }
            if (shares[i] > 0) {
                inMarket++;
            }
        }
        double ending = equity.length == 0 ? STARTING_CASH : equity[equity.length - 1];
        return new Metrics(
                round2(ending),
                round2((ending / STARTING_CASH - 1.0) * 100.0),
                round2(maxDrawdown * 100.0),
                equity.length == 0 ? 0 : round2(inMarket * 100.0 / equity.length),
                trades);
    }

    /** Sums the per-ticker accounts by date (carrying each ticker's last value over gaps). */
    static Portfolio portfolio(List<TickerResult> results) {
        List<TickerResult> ok = results.stream().filter(TickerResult::ok).toList();
        TreeSet<LocalDate> allDates = new TreeSet<>();
        ok.forEach(r -> allDates.addAll(r.dates()));
        List<LocalDate> dates = new ArrayList<>(allDates);
        double invested = ok.size() * STARTING_CASH;

        double[] bh = new double[dates.size()];
        double[] wf = new double[dates.size()];
        double[] fx = new double[dates.size()];
        for (TickerResult r : ok) {
            Map<LocalDate, Integer> index = new HashMap<>();
            for (int i = 0; i < r.dates().size(); i++) {
                index.put(r.dates().get(i), i);
            }
            double lastBh = STARTING_CASH;
            double lastWf = STARTING_CASH;
            double lastFx = STARTING_CASH;
            for (int d = 0; d < dates.size(); d++) {
                Integer i = index.get(dates.get(d));
                if (i != null) {
                    lastBh = r.buyHoldEquity().get(i);
                    lastWf = r.walkForwardEquity().get(i);
                    lastFx = r.fixedEquity().get(i);
                }
                bh[d] += lastBh;
                wf[d] += lastWf;
                fx[d] += lastFx;
            }
        }

        return new Portfolio(
                dates,
                rounded(bh),
                rounded(wf),
                rounded(fx),
                invested,
                portfolioMetrics(bh, invested, ok.stream().map(TickerResult::buyHold).toList()),
                portfolioMetrics(wf, invested, ok.stream().map(TickerResult::walkForward).toList()),
                portfolioMetrics(fx, invested, ok.stream().map(TickerResult::fixed).toList()),
                (int) ok.stream().filter(r -> Boolean.TRUE.equals(r.beat())).count(),
                (int) ok.stream().filter(r -> Boolean.TRUE.equals(r.fixedBeat())).count(),
                (int) ok.stream()
                        .filter(r -> r.walkForward().endingEquity() > r.fixed().endingEquity() + 0.005)
                        .count());
    }

    private static Metrics portfolioMetrics(double[] equity, double invested, List<Metrics> perTicker) {
        if (invested <= 0 || equity.length == 0) {
            return new Metrics(0, 0, 0, 0, 0);
        }
        double peak = invested;
        double maxDrawdown = 0;
        for (double value : equity) {
            peak = Math.max(peak, value);
            maxDrawdown = Math.max(maxDrawdown, (peak - value) / peak);
        }
        double ending = equity[equity.length - 1];
        return new Metrics(
                round2(ending),
                round2((ending / invested - 1.0) * 100.0),
                round2(maxDrawdown * 100.0),
                round2(perTicker.stream().mapToDouble(Metrics::timeInMarketPct).average().orElse(0)),
                perTicker.stream().mapToInt(Metrics::trades).sum());
    }

    private static List<Double> rounded(double[] values) {
        List<Double> out = new ArrayList<>(values.length);
        for (double value : values) {
            out.add(round2(value));
        }
        return out;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
