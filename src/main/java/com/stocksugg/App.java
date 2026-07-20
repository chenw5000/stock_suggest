package com.stocksugg;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.gemini.GeminiConfig;
import com.stocksugg.gemini.GeminiService;
import com.stocksugg.stock.BacktestDay;
import com.stocksugg.stock.GeminiStockAdvisor;
import com.stocksugg.stock.GeminiSuggestion;
import com.stocksugg.stock.MarketSession;
import com.stocksugg.stock.StockDataImporter;
import com.stocksugg.stock.SuggestionBacktester;
import com.stocksugg.stock.SuggestionStrategyOptimizer;
import com.stocksugg.stock.TickerList;
import com.stocksugg.web.WebServer;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class App {

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

    private static void adviseStocks(List<String> tickers) {
        try (Database db = new Database();
             GeminiService gemini = new GeminiService(new GeminiConfig("gemini-3.1-flash-lite"))) {
            StockRepository repository = new StockRepository(db);
            GeminiStockAdvisor advisor = new GeminiStockAdvisor(repository, gemini);
            System.out.println("Requesting Gemini suggestions for " + tickers + " ...");
            List<GeminiSuggestion> suggestions = advisor.adviseMany(tickers);
            for (GeminiSuggestion suggestion : suggestions) {
                GeminiStockAdvisor.printSuggestion(suggestion);
                System.out.println("Suggestion saved for " + suggestion.ticker()
                        + " on " + suggestion.asOf());
            }
            System.out.println("Batch complete: " + suggestions.size() + " suggestion(s) saved.");
        } catch (Exception e) {
            System.err.println("Advice failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * For trading days in [{@code from}, {@code to}] that already have a DB row for the ticker,
     * ask Gemini in batched API calls (multiple asOf packages per request) and update those rows.
     */
    private static void backfillSuggestions(String ticker, LocalDate from, LocalDate to) {
        String symbol = ticker.toUpperCase();
        System.out.println("Backfilling Gemini suggestions for " + symbol
                + " from " + from + " to " + to + " ...");
        try (Database db = new Database();
             GeminiService gemini = new GeminiService(new GeminiConfig("gemini-3.1-flash-lite"))) {
            StockRepository repository = new StockRepository(db);
            GeminiStockAdvisor advisor = new GeminiStockAdvisor(repository, gemini);
            List<LocalDate> dates = repository.findDatesInRange(symbol, from, to);
            if (dates.isEmpty()) {
                System.out.println("No stored trading days for " + symbol
                        + " in " + from + " .. " + to + ". Import Yahoo data first.");
                return;
            }

            System.out.println("Found " + dates.size() + " trading day(s); sending in Gemini batches of "
                    + GeminiStockAdvisor.DEFAULT_HISTORICAL_CHUNK_SIZE + " ...");
            List<GeminiSuggestion> suggestions = advisor.adviseAsOfMany(symbol, dates);
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
        List<String> tickers = TickerList.loadFromAdmin();
        System.out.println("Using tickers from admin." + TickerList.ADMIN_KEY + ": " + tickers);
        for (String ticker : tickers) {
            refreshStockData(ticker);
        }
        adviseStocks(tickers);
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

    private static void runStrategySearch(
            String ticker,
            LocalDate from,
            LocalDate to,
            double startingCash,
            int topN) {
        String symbol = ticker.trim().toUpperCase(Locale.ROOT);
        System.out.println("Strategy search " + symbol + " from " + from + " to " + to
                + " cash $" + String.format(Locale.US, "%,.2f", startingCash)
                + " top=" + topN);
        try (Database db = new Database()) {
            StockRepository repository = new StockRepository(db);
            List<BacktestDay> days = repository.findBacktestDays(symbol, from, to);
            if (days.isEmpty()) {
                System.err.println("No rows found for " + symbol + " in that date range.");
                return;
            }
            System.out.println("Trading days: " + days.size());

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

            SuggestionStrategyOptimizer.Candidate best = report.top().getFirst();
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

        if (backfill) {
            String ticker = argValue(argList, "--ticker", "AAPL");
            LocalDate from = LocalDate.parse(argValue(argList, "--from", "2026-07-01"));
            LocalDate to = LocalDate.parse(argValue(argList, "--to", "2026-07-14"));
            backfillSuggestions(ticker, from, to);
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
            runStrategySearch(ticker, from, to, cash, topN);
        }

        if (runBatch) {
            runBatchJob();
        }

        if (embedded) {
            // Local/dev only. Prefer Tomcat WAR deployment for the standard web app.
            WebServer.start(WebServer.DEFAULT_PORT);
            System.out.println("Embedded Javalin ready. Press Ctrl+C to stop.");
            return;
        }

        if (!runBatch && !backfill && !backtest && !optimize) {
            System.out.println("Nothing to run. Options:");
            System.out.println("  --batch              refresh Yahoo data + Gemini suggestions");
            System.out.println("  --backfill           Gemini backfill for historical days");
            System.out.println("      --ticker=AAPL --from=2026-07-01 --to=2026-07-14");
            System.out.println("  --backtest           suggestion-driven long-only backtest");
            System.out.println("      --ticker=QQQ --from=2026-01-01 --to=2026-07-17 --cash=10000");
            System.out.println("      --strategy=all-in|parts --parts=4");
            System.out.println("  --optimize           grid-search strategy params for higher return");
            System.out.println("      --ticker=QQQ --from=2026-01-01 --to=2026-07-17 --cash=10000 --top=15");
            System.out.println("  --embedded           start embedded Javalin on port 7070 (dev)");
            System.out.println("  --batch --embedded   batch then start embedded server");
            System.out.println("For Tomcat: mvn -DskipTests package  then copy target/stocksugg.war");
        }
    }
}
