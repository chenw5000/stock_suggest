package com.stocksugg.stock;

import com.stocksugg.App;
import com.stocksugg.db.Database;
import com.stocksugg.db.PerformanceRepository;
import com.stocksugg.db.StockRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Runs weekly performance snapshots one job at a time on a background thread (manual run,
 * backfill, or the automatic check after the daily batch) and exposes progress for the UI.
 */
public final class PerformanceRunner {

    public enum StartResult { STARTED, ALREADY_RUNNING, BATCH_RUNNING }

    public record Status(
            boolean running,
            String job,
            String trigger,
            LocalDate weekEnding,
            int weekIndex,
            int weekTotal,
            int tickerIndex,
            int tickerTotal,
            String ticker,
            Instant startedAt,
            Instant finishedAt,
            String message,
            String error) {}

    public static final String JOB_WEEK = "WEEK";
    public static final String JOB_BACKFILL = "BACKFILL";
    public static final String JOB_AUTO = "AUTO";

    private static final Object LOCK = new Object();
    private static Status status = new Status(
            false, null, null, null, 0, 0, 0, 0, null, null, null, null, null);

    private PerformanceRunner() {}

    public static Status status() {
        synchronized (LOCK) {
            return status;
        }
    }

    public static boolean isRunning() {
        return status().running();
    }

    /** Friday of the most recent complete week (Pacific session cutoff). */
    public static LocalDate latestWeekEnding() {
        return WeeklyPerformance.latestCompleteWeekEnding(MarketSession.lastCompleteSessionDate());
    }

    /**
     * Normalizes {@code requested} to its week's Friday (latest complete week when null) and
     * rejects weeks that have not finished yet.
     */
    public static LocalDate resolveWeekEnding(LocalDate requested) {
        LocalDate latest = latestWeekEnding();
        if (requested == null) {
            return latest;
        }
        LocalDate weekEnding = WeeklyPerformance.weekEnding(requested);
        if (weekEnding.isAfter(latest)) {
            throw new IllegalArgumentException("The week ending " + weekEnding
                    + " is not complete yet; latest complete week ends " + latest);
        }
        return weekEnding;
    }

    /** Manual run for one week (latest complete week when {@code requested} is null). */
    public static StartResult startWeek(LocalDate requested, long paramId, boolean recompute) {
        LocalDate weekEnding = resolveWeekEnding(requested);
        return start(JOB_WEEK, PerformanceWeek.TRIGGER_MANUAL, true, () -> {
            update(weekEnding, 1, 1, "Evaluating week ending " + weekEnding + "…");
            Optional<PerformanceWeek> saved = runWeek(
                    weekEnding, paramId, recompute, PerformanceWeek.TRIGGER_MANUAL, true, progressFor(weekEnding));
            return saved.map(PerformanceRunner::summary)
                    .orElse("Week ending " + weekEnding + ": nothing saved.");
        });
    }

    /** Fills the last {@code weeks} weeks (oldest first); skips weeks already up to date unless recompute. */
    public static StartResult startBackfill(int weeks, long paramId, boolean recompute) {
        if (weeks < 1 || weeks > 104) {
            throw new IllegalArgumentException("weeks must be between 1 and 104");
        }
        LocalDate latest = latestWeekEnding();
        return start(JOB_BACKFILL, PerformanceWeek.TRIGGER_BACKFILL, true, () -> {
            int saved = 0;
            int upToDate = 0;
            int noData = 0;
            for (int i = 0; i < weeks; i++) {
                LocalDate weekEnding = latest.minusWeeks(weeks - 1 - i);
                update(weekEnding, i + 1, weeks, "Backfill week " + (i + 1) + " of " + weeks
                        + " (week ending " + weekEnding + ")…");
                if (!recompute && isUpToDate(weekEnding, paramId)) {
                    upToDate++;
                    continue;
                }
                Optional<PerformanceWeek> week;
                try {
                    week = runWeek(weekEnding, paramId, recompute,
                            PerformanceWeek.TRIGGER_BACKFILL, false, progressFor(weekEnding));
                } catch (IllegalArgumentException e) {
                    week = Optional.empty();
                }
                if (week.isPresent()) {
                    saved++;
                } else {
                    noData++;
                }
            }
            return "Backfill done: " + saved + " week(s) saved, " + upToDate + " already up to date, "
                    + noData + " without enough data.";
        });
    }

    /**
     * Computes the latest complete week when it has no snapshot yet, or when its snapshot was
     * taken before that week's last trading day was stored. Used after the daily batch and on startup.
     */
    public static StartResult startLatestIfNeeded(long paramId) {
        return start(JOB_AUTO, PerformanceWeek.TRIGGER_SCHEDULED, false, () -> {
            LocalDate weekEnding = latestWeekEnding();
            if (isUpToDate(weekEnding, paramId)) {
                return "Week ending " + weekEnding + " is up to date.";
            }
            update(weekEnding, 1, 1, "Scheduled evaluation for week ending " + weekEnding + "…");
            Optional<PerformanceWeek> saved = runWeek(weekEnding, paramId, false,
                    PerformanceWeek.TRIGGER_SCHEDULED, true, progressFor(weekEnding));
            return saved.map(PerformanceRunner::summary)
                    .orElse("Week ending " + weekEnding + ": nothing saved.");
        });
    }

    /**
     * Evaluates and saves one week synchronously. When {@code saveIfEmpty} is false and no ticker
     * has enough data, nothing is saved and the result is empty.
     */
    public static Optional<PerformanceWeek> runWeek(
            LocalDate weekEnding,
            long paramId,
            boolean recompute,
            String triggerSource,
            boolean saveIfEmpty,
            WeeklyPerformance.Progress progress) throws Exception {
        List<String> tickers = TickerList.loadFromAdmin();
        try (Database db = new Database()) {
            WeeklyPerformance.Evaluation evaluation = WeeklyPerformance.evaluate(
                    db, weekEnding, paramId, tickers, recompute, triggerSource, progress);
            if (!saveIfEmpty && evaluation.week().evaluated() == 0) {
                System.out.println("Weekly performance " + weekEnding + ": no ticker with enough data; not saved.");
                return Optional.empty();
            }
            PerformanceWeek saved = new PerformanceRepository(db).save(evaluation.week(), evaluation.rows());
            System.out.println("Weekly performance saved: " + summary(saved));
            return Optional.of(saved);
        }
    }

    /** True when a snapshot exists for the week and was taken on the week's last stored trading day. */
    public static boolean isUpToDate(LocalDate weekEnding, long paramId) throws Exception {
        try (Database db = new Database()) {
            Optional<PerformanceWeek> saved = new PerformanceRepository(db).findWeek(weekEnding, paramId);
            if (saved.isEmpty()) {
                return false;
            }
            Optional<LocalDate> anchor = WeeklyPerformance.anchorFor(new StockRepository(db), weekEnding);
            return anchor.isPresent() && anchor.get().equals(saved.get().asOfDate());
        }
    }

    public static String summary(PerformanceWeek week) {
        return String.format(java.util.Locale.US,
                "week ending %s (as of %s): %d beat / %d not beat / %d skipped; strategy $%,.2f vs buy&hold $%,.2f",
                week.weekEnding(), week.asOfDate(), week.beatCount(), week.notBeatCount(), week.skipped(),
                week.strategyTotal(), week.buyHoldTotal());
    }

    @FunctionalInterface
    private interface Job {
        String run() throws Exception;
    }

    private static StartResult start(String job, String trigger, boolean blockOnBatch, Job body) {
        synchronized (LOCK) {
            if (status.running()) {
                return StartResult.ALREADY_RUNNING;
            }
            if (blockOnBatch && App.isBatchRunning()) {
                return StartResult.BATCH_RUNNING;
            }
            status = new Status(true, job, trigger, null, 0, 0, 0, 0, null,
                    Instant.now(), null, "Starting…", null);
        }
        Thread worker = new Thread(() -> {
            String message = null;
            String error = null;
            try {
                message = body.run();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                System.err.println("Weekly performance " + job + " failed: " + error);
                e.printStackTrace();
            } finally {
                synchronized (LOCK) {
                    Status s = status;
                    status = new Status(false, s.job(), s.trigger(), s.weekEnding(),
                            s.weekIndex(), s.weekTotal(), s.tickerIndex(), s.tickerTotal(), s.ticker(),
                            s.startedAt(), Instant.now(),
                            error == null ? message : "Failed.", error);
                }
            }
        }, "stocksugg-performance");
        worker.setDaemon(true);
        worker.start();
        return StartResult.STARTED;
    }

    private static void update(LocalDate weekEnding, int weekIndex, int weekTotal, String message) {
        synchronized (LOCK) {
            Status s = status;
            status = new Status(true, s.job(), s.trigger(), weekEnding, weekIndex, weekTotal,
                    0, 0, null, s.startedAt(), null, message, null);
        }
    }

    private static WeeklyPerformance.Progress progressFor(LocalDate weekEnding) {
        return (index, total, ticker) -> {
            synchronized (LOCK) {
                Status s = status;
                status = new Status(true, s.job(), s.trigger(), weekEnding, s.weekIndex(), s.weekTotal(),
                        index, total, ticker, s.startedAt(), null, s.message(), null);
            }
        };
    }
}
