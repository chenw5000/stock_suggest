package com.stocksugg.stock;

import com.stocksugg.App;
import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.db.WalkForwardRepository;
import com.stocksugg.web.SuggestApi;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Runs one walk-forward simulation at a time on a background thread and exposes progress for the UI. */
public final class WalkForwardRunner {

    public enum StartResult { STARTED, ALREADY_RUNNING, BATCH_RUNNING }

    public record Status(
            boolean running,
            String cadence,
            LocalDate testFrom,
            LocalDate testTo,
            int tickerIndex,
            int tickerTotal,
            String ticker,
            Instant startedAt,
            Instant finishedAt,
            String message,
            String error,
            Long runId) {}

    private static final Object LOCK = new Object();
    private static Status status =
            new Status(false, null, null, null, 0, 0, null, null, null, null, null, null);

    private WalkForwardRunner() {}

    public static Status status() {
        synchronized (LOCK) {
            return status;
        }
    }

    /** Default test span (see {@link WalkForward#defaultSpan}). */
    public static LocalDate[] defaultSpan(long paramId) throws Exception {
        try (Database db = new Database()) {
            return WalkForward.defaultSpan(new StockRepository(db), paramId, LocalDate.now());
        }
    }

    /** Starts a run; null {@code from} / {@code to} use the default span. */
    public static StartResult start(
            WalkForward.Cadence cadence, LocalDate from, LocalDate to, long paramId, boolean recompute)
            throws Exception {
        if (from == null || to == null) {
            LocalDate[] span = defaultSpan(paramId);
            from = from == null ? span[0] : from;
            to = to == null ? span[1] : to;
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        LocalDate testFrom = from;
        LocalDate testTo = to;
        synchronized (LOCK) {
            if (status.running()) {
                return StartResult.ALREADY_RUNNING;
            }
            if (App.isBatchRunning()) {
                return StartResult.BATCH_RUNNING;
            }
            status = new Status(true, cadence.name(), testFrom, testTo, 0, 0, null,
                    Instant.now(), null, "Starting…", null, null);
        }
        Thread worker = new Thread(() -> {
            String message = null;
            String error = null;
            Long runId = null;
            try {
                runId = runAndSave(cadence, testFrom, testTo, paramId, recompute);
                message = "Done.";
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                System.err.println("Walk-forward failed: " + error);
                e.printStackTrace();
            } finally {
                synchronized (LOCK) {
                    Status s = status;
                    status = new Status(false, s.cadence(), s.testFrom(), s.testTo(),
                            s.tickerIndex(), s.tickerTotal(), s.ticker(), s.startedAt(), Instant.now(),
                            error == null ? message : "Failed.", error, runId);
                }
            }
        }, "stocksugg-walkforward");
        worker.setDaemon(true);
        worker.start();
        return StartResult.STARTED;
    }

    /** Runs synchronously, saves the report, and returns the saved run id. */
    public static long runAndSave(
            WalkForward.Cadence cadence, LocalDate testFrom, LocalDate testTo, long paramId, boolean recompute)
            throws Exception {
        List<String> tickers = TickerList.loadFromAdmin();
        try (Database db = new Database()) {
            WalkForward.Report report = WalkForward.run(db, tickers, cadence, testFrom, testTo, paramId, recompute,
                    (index, total, ticker) -> {
                        synchronized (LOCK) {
                            Status s = status;
                            status = new Status(s.running(), s.cadence(), s.testFrom(), s.testTo(),
                                    index, total, ticker, s.startedAt(), null,
                                    "Simulating " + ticker + " (" + index + " of " + total + ")…", null, null);
                        }
                    });
            WalkForward.Portfolio p = report.portfolio();
            boolean any = report.included() > 0;
            WalkForwardRepository.RunSummary summary = new WalkForwardRepository.RunSummary(
                    0, report.cadence(), testFrom, testTo, paramId, report.tickersTotal(), report.included(),
                    any ? p.walkForwardMetrics().returnPct() : null,
                    any ? p.buyHoldMetrics().returnPct() : null,
                    any ? p.fixedMetrics().returnPct() : null,
                    report.durationMs(),
                    null);
            long id = new WalkForwardRepository(db).save(summary, SuggestApi.mapper().writeValueAsString(report));
            System.out.println(String.format(Locale.US,
                    "Walk-forward %s %s..%s saved (id %d): %d of %d tickers; walk-forward %+.2f%%, "
                            + "buy&hold %+.2f%%, fixed %+.2f%%",
                    report.cadence(), testFrom, testTo, id, report.included(), report.tickersTotal(),
                    p.walkForwardMetrics().returnPct(), p.buyHoldMetrics().returnPct(),
                    p.fixedMetrics().returnPct()));
            return id;
        }
    }
}
