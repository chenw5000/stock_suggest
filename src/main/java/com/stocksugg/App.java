package com.stocksugg;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.db.StrategyOptimizeRepository;
import com.stocksugg.gemini.GeminiConfig;
import com.stocksugg.gemini.GeminiService;
import com.stocksugg.stock.BacktestDay;
import com.stocksugg.stock.GeminiStockAdvisor;
import com.stocksugg.stock.GeminiSuggestion;
import com.stocksugg.stock.MarketSession;
import com.stocksugg.stock.StockDataImporter;
import com.stocksugg.stock.StockRow;
import com.stocksugg.stock.StrategyOptimize;
import com.stocksugg.stock.SuggestionBacktester;
import com.stocksugg.stock.SuggestionStrategyOptimizer;
import com.stocksugg.stock.TechnicalIndicators;
import com.stocksugg.stock.TickerList;
import com.stocksugg.web.WebServer;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.sql.SQLException;

public class App {

    private static final long BACKFILL_MONTH_WAIT_MILLIS = 70 * 1000L;
    /** Gemini daily advice: tickers per API call. */
    private static final int BATCH_ADVICE_CHUNK_SIZE = 10;
    /** Pause between Gemini advice chunks to avoid rate limits. */
    private static final long BATCH_ADVICE_COOLDOWN_MILLIS = 60_000L;

    record DateRange(LocalDate from, LocalDate to) {}

    private static void refreshStockData(String ticker) {
        try (Database db = new Database()) {
            StockRepository repository = new StockRepository(db);
            Optional<LocalDate> latest = repository.findLatestDate(ticker);
            LocalDate lastComplete = MarketSession.lastCompleteSessionDate();

            LocalDate from;
            if (latest.isPresent()) {
                // Start the day after the latest stored row so existing OHLCV + suggestions
                // (thesis, risks, etc.) are never deleted/reinserted.
                from = latest.get().plusDays(1);
                System.out.println(ticker + " last DB date: " + latest.get()
                        + " - loading new data from " + from);
            } else {
                from = LocalDate.now().minusYears(2);
                System.out.println(ticker + " has no DB records - loading last 2 years from " + from);
            }

            if (from.isAfter(lastComplete)) {
                System.out.println(ticker + ": skip download — market not closed yet in PT "
                        + "(load only through " + lastComplete + ")");
                return;
            }

            System.out.println(ticker + ": importing through last complete session " + lastComplete);
            loadHistoricalData(db, ticker, from);
        } catch (Exception e) {
            System.err.println("Refresh failed for " + ticker + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void loadHistoricalData(Database db, String ticker, LocalDate from) {
        try {
            StockDataImporter importer = new StockDataImporter(db);
            System.out.println("Downloading " + ticker + " from Yahoo since " + from + " ...");
            int saved = importer.importDaily(ticker, from);
            System.out.println(ticker + " saved rows: " + saved);
        } catch (Exception e) {
            System.err.println("Import failed for " + ticker + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void adviseStocks(List<String> tickers, long paramId) {
        if (tickers == null || tickers.isEmpty()) {
            System.out.println("No tickers to advise; skipping Gemini batch.");
            return;
        }

        int totalSaved = 0;
        int chunkCount = (tickers.size() + BATCH_ADVICE_CHUNK_SIZE - 1) / BATCH_ADVICE_CHUNK_SIZE;
        System.out.println("Requesting Gemini suggestions for " + tickers.size()
                + " ticker(s) in " + chunkCount + " chunk(s) of up to "
                + BATCH_ADVICE_CHUNK_SIZE + " (param_id=" + paramId + ") ...");

        try (Database db = new Database();
             GeminiService gemini = new GeminiService(new GeminiConfig("gemini-3.5-flash-lite"))) {
            GeminiStockAdvisor advisor = new GeminiStockAdvisor(db, gemini);

            for (int i = 0; i < tickers.size(); i += BATCH_ADVICE_CHUNK_SIZE) {
                int chunkIndex = (i / BATCH_ADVICE_CHUNK_SIZE) + 1;
                List<String> chunk = tickers.subList(
                        i, Math.min(i + BATCH_ADVICE_CHUNK_SIZE, tickers.size()));
                System.out.println("Gemini advice chunk " + chunkIndex + "/" + chunkCount
                        + ": " + chunk);

                try {
                    List<GeminiSuggestion> suggestions = advisor.adviseMany(chunk, paramId);
                    for (GeminiSuggestion suggestion : suggestions) {
                        GeminiStockAdvisor.printSuggestion(suggestion);
                        System.out.println("Suggestion saved for " + suggestion.ticker()
                                + " on " + suggestion.asOf());
                    }
                    totalSaved += suggestions.size();
                } catch (Exception e) {
                    System.err.println("Advice failed for chunk " + chunkIndex + "/" + chunkCount
                            + " " + chunk + ": " + e.getMessage());
                    e.printStackTrace();
                }

                if (i + BATCH_ADVICE_CHUNK_SIZE < tickers.size()) {
                    System.out.println("Waiting 1 minute for Gemini cooldown...");
                    try {
                        Thread.sleep(BATCH_ADVICE_COOLDOWN_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.err.println("Advice interrupted during cooldown; stopping.");
                        break;
                    }
                }
            }
            System.out.println("Batch complete: " + totalSaved + " suggestion(s) saved.");
        } catch (Exception e) {
            System.err.println("Advice failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * For trading days in [{@code from}, {@code to}] that already have a DB row for the ticker,
     * split the interval into calendar months and backfill each month separately. Wait two minutes
     * between months to let the Gemini API cool down.
     */
    private static void backfillSuggestions(
            String ticker, LocalDate from, LocalDate to, long paramId) {
        List<DateRange> ranges = monthlyRanges(from, to);
        String symbol = ticker.toUpperCase(Locale.ROOT);
        System.out.println("Backfill for " + symbol + " split into "
                + ranges.size() + " monthly interval(s); param_id=" + paramId);

        for (int i = 0; i < ranges.size(); i++) {
            DateRange range = ranges.get(i);
            System.out.println("Monthly backfill " + (i + 1) + "/" + ranges.size()
                    + ": " + range.from() + " to " + range.to());
            backfillSuggestionsForRange(ticker, range.from(), range.to(), paramId);

            if (i < ranges.size() - 1) {
                System.out.println("Waiting 70 seconds for Gemini cooldown...");
                try {
                    Thread.sleep(BACKFILL_MONTH_WAIT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Backfill interrupted during cooldown; stopping.");
                    return;
                }
            }
        }
    }

    /**
     * Converts an inclusive date interval into calendar-month intervals. The first and last
     * intervals are clipped to the requested dates.
     */
    static List<DateRange> monthlyRanges(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }

        List<DateRange> ranges = new ArrayList<>();
        LocalDate rangeStart = from;
        while (!rangeStart.isAfter(to)) {
            LocalDate endOfMonth = YearMonth.from(rangeStart).atEndOfMonth();
            LocalDate rangeEnd = endOfMonth.isBefore(to) ? endOfMonth : to;
            ranges.add(new DateRange(rangeStart, rangeEnd));
            rangeStart = rangeEnd.plusDays(1);
        }
        return List.copyOf(ranges);
    }

    /**
     * Performs one Gemini backfill call for one monthly interval.
     */
    private static void backfillSuggestionsForRange(
            String ticker,
            LocalDate from,
            LocalDate to,
            long paramId) {
        String symbol = ticker.toUpperCase();
        System.out.println("Backfilling Gemini suggestions for " + symbol
                + " from " + from + " to " + to + " (param_id=" + paramId + ") ...");
        try (Database db = new Database();
             GeminiService gemini = new GeminiService(new GeminiConfig("gemini-3.5-flash-lite"))) {
            StockRepository repository = new StockRepository(db);
            GeminiStockAdvisor advisor = new GeminiStockAdvisor(db, gemini);
            List<LocalDate> dates = repository.findDatesInRange(symbol, from, to);
            if (dates.isEmpty()) {
                System.out.println("No stored trading days for " + symbol
                        + " in " + from + " .. " + to + ". Import Yahoo data first.");
                return;
            }

            System.out.println("Found " + dates.size() + " trading day(s); sending in Gemini batches of "
                    + GeminiStockAdvisor.DEFAULT_HISTORICAL_CHUNK_SIZE + " ...");
            List<GeminiSuggestion> suggestions = advisor.adviseAsOfMany(symbol, dates, paramId);
            for (GeminiSuggestion suggestion : suggestions) {
                GeminiStockAdvisor.printSuggestion(suggestion);
                System.out.println("Suggestion saved for " + suggestion.ticker()
                        + " on " + suggestion.asOf());
            }
            System.out.println("Backfill complete for " + symbol
                    + ": " + suggestions.size() + " saved of " + dates.size() + " trading day(s).");
        } catch (Exception e) {
            System.err.println("Backfill failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static final Object BATCH_LOCK = new Object();
    private static volatile boolean batchRunning;

    /** True while a batch (Yahoo refresh + Gemini) is in progress. */
    public static boolean isBatchRunning() {
        return batchRunning;
    }

    /**
     * Starts {@link #runBatchJob()} on a background thread if none is already running.
     *
     * @return {@code true} if the job was started, {@code false} if one was already running
     */
    public static boolean startBatchJobAsync() {
        synchronized (BATCH_LOCK) {
            if (batchRunning) {
                return false;
            }
            batchRunning = true;
        }
        Thread worker = new Thread(() -> {
            try {
                runBatchJob();
            } finally {
                synchronized (BATCH_LOCK) {
                    batchRunning = false;
                }
            }
        }, "stocksugg-batch");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public static void runBatchJob() {
        runBatchJob(GeminiStockAdvisor.DEFAULT_PARAM_ID);
    }

    public static void runBatchJob(long paramId) {
        List<String> tickers = TickerList.loadFromAdmin();
        System.out.println("Using tickers from admin." + TickerList.ADMIN_KEY + ": " + tickers);
        for (String ticker : tickers) {
            refreshStockData(ticker);
        }
        adviseStocks(tickers, paramId);
    }

    /**
     * Recalculates Wilder RSI(14) from all stored closes for every ticker in the admin watchlist.
     * Only the {@code rsi14} column is updated; OHLCV and suggestion fields are left unchanged.
     */
    public static void updateRsi14ForWatchlist() {
        List<String> tickers = TickerList.loadFromAdmin();
        System.out.println("Updating RSI(14) for watchlist: " + tickers);

        try (Database db = new Database()) {
            StockRepository repository = new StockRepository(db);
            int totalUpdated = 0;

            for (String ticker : tickers) {
                try {
                    List<StockRow> rows = repository.findAllBars(ticker);
                    if (rows.isEmpty()) {
                        System.out.println(ticker + ": no stored rows; skipped.");
                        continue;
                    }

                    double[] closes = new double[rows.size()];
                    for (int i = 0; i < rows.size(); i++) {
                        closes[i] = rows.get(i).close();
                    }
                    Float[] values = TechnicalIndicators.relativeStrengthIndex(
                            closes, TechnicalIndicators.RSI_PERIOD);

                    Map<LocalDate, Float> valuesByDate = new LinkedHashMap<>();
                    int populated = 0;
                    for (int i = 0; i < rows.size(); i++) {
                        valuesByDate.put(rows.get(i).date(), values[i]);
                        if (values[i] != null) {
                            populated++;
                        }
                    }

                    int updated = repository.updateRsi14(ticker, valuesByDate);
                    totalUpdated += updated;
                    System.out.println(ticker + ": updated " + updated + " row(s); "
                            + populated + " RSI value(s), "
                            + (rows.size() - populated) + " warm-up null(s).");
                } catch (Exception e) {
                    System.err.println(ticker + ": RSI update failed: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            System.out.println("RSI(14) watchlist update complete: "
                    + totalUpdated + " row(s) updated.");
        } catch (Exception e) {
            System.err.println("RSI watchlist update failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runBacktest(
            String ticker,
            LocalDate from,
            LocalDate to,
            double startingCash,
            String strategy,
            int parts) {
        String symbol = ticker.trim().toUpperCase(Locale.ROOT);
        String mode = strategy == null ? "all-in" : strategy.trim().toLowerCase(Locale.ROOT);
        System.out.println("Backtest " + symbol + " from " + from + " to " + to
                + " starting cash $" + String.format(Locale.US, "%,.2f", startingCash)
                + " strategy=" + mode
                + ("parts".equals(mode) ? (" parts=" + parts) : ""));
        try (Database db = new Database()) {
            StockRepository repository = new StockRepository(db);
            List<BacktestDay> days = repository.findBacktestDays(symbol, from, to);
            if (days.isEmpty()) {
                System.err.println("No rows found for " + symbol + " in that date range.");
                return;
            }

            long withAction = days.stream()
                    .filter(d -> d.suggestedAction() != null && !d.suggestedAction().isBlank())
                    .count();
            System.out.println("Trading days: " + days.size()
                    + " (with suggestedAction: " + withAction + ")");
            System.out.println("First day: " + days.getFirst().date()
                    + " close=" + days.getFirst().close()
                    + " action=" + days.getFirst().suggestedAction());
            System.out.println("Last day:  " + days.getLast().date()
                    + " close=" + days.getLast().close()
                    + " action=" + days.getLast().suggestedAction());
            if ("parts".equals(mode)) {
                System.out.printf(Locale.US, "Part size: $%,.2f (%d equal parts)%n",
                        startingCash / parts, parts);
            }

            SuggestionBacktester.Result result = "parts".equals(mode)
                    ? SuggestionBacktester.runParts(startingCash, parts, days)
                    : SuggestionBacktester.run(startingCash, days);

            System.out.println("--- Trades ---");
            for (SuggestionBacktester.Trade trade : result.trades()) {
                System.out.printf(Locale.US,
                        "%s  %-24s  %+4d @ %.2f  cash=$%,.2f  shares=%d  equity=$%,.2f%n",
                        trade.day().date(),
                        trade.event(),
                        trade.sharesDelta(),
                        trade.price(),
                        trade.cashAfter(),
                        trade.sharesAfter(),
                        trade.equityAfter());
            }

            System.out.println("--- Summary ---");
            System.out.printf(Locale.US, "Starting cash: $%,.2f%n", result.startingCash());
            System.out.printf(Locale.US, "Ending cash:   $%,.2f%n", result.endingCash());
            System.out.printf(Locale.US, "Ending shares: %d @ last close %.2f%n",
                    result.endingShares(), result.endingClose());
            System.out.printf(Locale.US, "Ending equity: $%,.2f%n", result.endingEquity());
            System.out.printf(Locale.US, "Return:        %+.2f%%%n", result.returnPct());
            System.out.printf(Locale.US, "Buys / sells / skipped buys: %d / %d / %d%n",
                    result.buyCount(), result.sellCount(), result.skippedBuys());
        } catch (Exception e) {
            System.err.println("Backtest failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Grid-searches strategies for {@code ticker} in [{@code from}, {@code to}],
     * upserts the best ({@code rank=1}) into {@code strategy_optimize}, and returns it.
     */
    public static StrategyOptimize optimizeAndSave(
            String ticker,
            LocalDate from,
            LocalDate to,
            double startingCash,
            int topN,
            long paramId) throws Exception {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        if (startingCash <= 0) {
            throw new IllegalArgumentException("startingCash must be > 0");
        }
        if (topN < 1) {
            throw new IllegalArgumentException("topN must be >= 1");
        }

        String symbol = ticker.trim().toUpperCase(Locale.ROOT);
        System.out.println("Strategy search " + symbol + " from " + from + " to " + to
                + " cash $" + String.format(Locale.US, "%,.2f", startingCash)
                + " top=" + topN
                + " param_id=" + paramId);

        try (Database db = new Database()) {
            StockRepository repository = new StockRepository(db);
            List<BacktestDay> days = repository.findBacktestDays(symbol, from, to, paramId);
            if (days.isEmpty()) {
                throw new IllegalArgumentException(
                        "No rows found for " + symbol + " in " + from + " .. " + to);
            }
            long withAction = days.stream()
                    .filter(d -> d.suggestedAction() != null && !d.suggestedAction().isBlank())
                    .count();
            System.out.println("Trading days: " + days.size()
                    + " (with suggestedAction for param_id=" + paramId + ": " + withAction + ")");

            SuggestionStrategyOptimizer.Report report =
                    SuggestionStrategyOptimizer.search(startingCash, days, topN);

            System.out.println("--- Baselines ---");
            printSearchResult("Buy & hold", report.buyAndHold());
            printSearchResult("All-in (BUY all / SELL·AVOID all)", report.baselineAllIn());
            printSearchResult("4 equal parts", report.baselineParts4());

            System.out.println("--- Top " + report.top().size() + " strategies by ending equity ---");
            int rank = 1;
            for (SuggestionStrategyOptimizer.Candidate candidate : report.top()) {
                SuggestionBacktester.Result r = candidate.result();
                System.out.printf(Locale.US,
                        "#%d  equity=$%,.2f  return=%+.2f%%  buys=%d sells=%d skippedBuys=%d%n",
                        rank++,
                        r.endingEquity(),
                        r.returnPct(),
                        r.buyCount(),
                        r.sellCount(),
                        r.skippedBuys());
                System.out.println("    " + candidate.strategy());
            }

            if (report.top().isEmpty()) {
                throw new IllegalStateException("No strategy candidates produced for " + symbol);
            }

            SuggestionStrategyOptimizer.Candidate best = report.top().getFirst();
            StrategyOptimizeRepository optimizeRepo = new StrategyOptimizeRepository(db);
            Optional<StrategyOptimize> existing = optimizeRepo.findBestNearWindow(
                    symbol, paramId, from, to, 7);
            existing.ifPresent(old -> {
                try {
                    int removed = optimizeRepo.deleteByWindow(
                            old.ticker(), old.paramId(), old.fromDate(), old.toDate());
                    if (removed > 0) {
                        System.out.println("Replaced prior saved strategy for "
                                + old.ticker() + " " + old.fromDate() + " .. " + old.toDate()
                                + " (" + removed + " row(s) deleted)");
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(
                            "Failed to delete prior strategy_optimize row: " + e.getMessage(), e);
                }
            });
            StrategyOptimize bestRow = StrategyOptimize.fromSearch(
                    symbol,
                    paramId,
                    from,
                    to,
                    startingCash,
                    best.strategy(),
                    best.result(),
                    report.buyAndHold(),
                    1);
            optimizeRepo.upsert(bestRow);
            System.out.println("Saved best strategy (rank=1) to strategy_optimize for "
                    + symbol + " " + from + " .. " + to
                    + " equity=$" + String.format(Locale.US, "%,.2f", best.result().endingEquity())
                    + " return=" + String.format(Locale.US, "%+.2f%%", best.result().returnPct()));

            System.out.println("--- Best strategy trades ---");
            for (SuggestionBacktester.Trade trade : best.result().trades()) {
                if (trade.event().startsWith("SKIP_")) {
                    continue;
                }
                System.out.printf(Locale.US,
                        "%s  %-20s %+4d @ %.2f  cash=$%,.2f  shares=%d  equity=$%,.2f  conf=%s%n",
                        trade.day().date(),
                        trade.event(),
                        trade.sharesDelta(),
                        trade.price(),
                        trade.cashAfter(),
                        trade.sharesAfter(),
                        trade.equityAfter(),
                        trade.day().confidence() == null ? "—" : String.format(Locale.US, "%.2f",
                                trade.day().confidence()));
            }

            return optimizeRepo.findByKey(symbol, paramId, from, to, 1)
                    .orElse(bestRow);
        }
    }

    private static void runStrategySearch(
            String ticker,
            LocalDate from,
            LocalDate to,
            double startingCash,
            int topN,
            long paramId) {
        try {
            optimizeAndSave(ticker, from, to, startingCash, topN, paramId);
        } catch (Exception e) {
            System.err.println("Strategy search failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printSearchResult(String label, SuggestionBacktester.Result result) {
        System.out.printf(Locale.US, "%-40s  equity=$%,.2f  return=%+.2f%%%n",
                label, result.endingEquity(), result.returnPct());
    }

    private static String argValue(List<String> args, String name, String defaultValue) {
        String prefix = name + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                String value = arg.substring(prefix.length()).trim();
                return value.isEmpty() ? defaultValue : value;
            }
        }
        return defaultValue;
    }

    public static void main(String[] args) {
        System.out.println("StockSugg starting...");

        List<String> argList = Arrays.asList(args);
        boolean runBatch = argList.contains("--batch");
        boolean embedded = argList.contains("--embedded");
        boolean backfill = argList.contains("--backfill");
        boolean backtest = argList.contains("--backtest");
        boolean optimize = argList.contains("--optimize");
        boolean updateRsi = argList.contains("--update-rsi");

        if (updateRsi) {
            updateRsi14ForWatchlist();
        }

        if (backfill) {
            String ticker = argValue(argList, "--ticker", "AAPL");
            LocalDate from = LocalDate.parse(argValue(argList, "--from", "2026-07-01"));
            LocalDate to = LocalDate.parse(argValue(argList, "--to", "2026-07-14"));
            long paramId = Long.parseLong(argValue(argList, "--param-id",
                    String.valueOf(GeminiStockAdvisor.DEFAULT_PARAM_ID)));
            backfillSuggestions(ticker, from, to, paramId);
        }

        if (backtest) {
            String ticker = argValue(argList, "--ticker", "QQQ");
            LocalDate from = LocalDate.parse(argValue(argList, "--from", "2026-01-01"));
            LocalDate to = LocalDate.parse(argValue(argList, "--to", "2026-07-17"));
            double cash = Double.parseDouble(argValue(argList, "--cash", "10000"));
            String strategy = argValue(argList, "--strategy", "all-in");
            int parts = Integer.parseInt(argValue(argList, "--parts", "4"));
            runBacktest(ticker, from, to, cash, strategy, parts);
        }

        if (optimize) {
            String ticker = argValue(argList, "--ticker", "QQQ");
            LocalDate from = LocalDate.parse(argValue(argList, "--from", "2026-01-01"));
            LocalDate to = LocalDate.parse(argValue(argList, "--to", "2026-07-17"));
            double cash = Double.parseDouble(argValue(argList, "--cash", "10000"));
            int topN = Integer.parseInt(argValue(argList, "--top", "15"));
            long paramId = Long.parseLong(argValue(argList, "--param-id",
                    String.valueOf(GeminiStockAdvisor.DEFAULT_PARAM_ID)));
            runStrategySearch(ticker, from, to, cash, topN, paramId);
        }

        if (runBatch) {
            long paramId = Long.parseLong(argValue(argList, "--param-id",
                    String.valueOf(GeminiStockAdvisor.DEFAULT_PARAM_ID)));
            runBatchJob(paramId);
        }

        if (embedded) {
            // Local/dev only. Prefer Tomcat WAR deployment for the standard web app.
            WebServer.start(WebServer.DEFAULT_PORT);
            System.out.println("Embedded Javalin ready. Press Ctrl+C to stop.");
            return;
        }

        if (!runBatch && !backfill && !backtest && !optimize && !updateRsi) {
            System.out.println("Nothing to run. Options:");
            System.out.println("  --batch              refresh Yahoo data + Gemini suggestions");
            System.out.println("      --param-id=1      tuning_params.id (num_date_point → lookback); default 1");
            System.out.println("  --update-rsi         recalculate RSI(14) for all watchlist tickers");
            System.out.println("  --backfill           Gemini backfill for historical days");
            System.out.println("      --ticker=AAPL --from=2026-07-01 --to=2026-07-14 --param-id=1");
            System.out.println("  --backtest           suggestion-driven long-only backtest");
            System.out.println("      --ticker=QQQ --from=2026-01-01 --to=2026-07-17 --cash=10000");
            System.out.println("      --strategy=all-in|parts --parts=4");
            System.out.println("  --optimize           grid-search strategy params for higher return");
            System.out.println("      --ticker=QQQ --from=2026-01-01 --to=2026-07-17 --cash=10000 --top=15");
            System.out.println("      --param-id=1      suggestions version (tuning_params.id); default 1");
            System.out.println("  --embedded           start embedded Javalin on port 7070 (dev)");
            System.out.println("  --batch --embedded   batch then start embedded server");
            System.out.println("For Tomcat: mvn -DskipTests package  then copy target/stocksugg.war");
        }
    }
}
