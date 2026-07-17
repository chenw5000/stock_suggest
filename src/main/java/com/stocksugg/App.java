package com.stocksugg;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.gemini.GeminiConfig;
import com.stocksugg.gemini.GeminiService;
import com.stocksugg.stock.GeminiStockAdvisor;
import com.stocksugg.stock.GeminiSuggestion;
import com.stocksugg.stock.MarketSession;
import com.stocksugg.stock.StockDataImporter;
import com.stocksugg.stock.TickerList;
import com.stocksugg.web.WebServer;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
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

    private static void runBatchJob() {
        List<String> tickers = TickerList.loadFromAdmin();
        System.out.println("Using tickers from admin." + TickerList.ADMIN_KEY + ": " + tickers);
        for (String ticker : tickers) {
            refreshStockData(ticker);
        }
        adviseStocks(tickers);
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

        if (backfill) {
            String ticker = argValue(argList, "--ticker", "AAPL");
            LocalDate from = LocalDate.parse(argValue(argList, "--from", "2026-07-01"));
            LocalDate to = LocalDate.parse(argValue(argList, "--to", "2026-07-14"));
            backfillSuggestions(ticker, from, to);
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

        if (!runBatch && !backfill) {
            System.out.println("Nothing to run. Options:");
            System.out.println("  --batch              refresh Yahoo data + Gemini suggestions");
            System.out.println("  --backfill           Gemini backfill for historical days");
            System.out.println("      --ticker=AAPL --from=2026-07-01 --to=2026-07-14");
            System.out.println("  --embedded           start embedded Javalin on port 7070 (dev)");
            System.out.println("  --batch --embedded   batch then start embedded server");
            System.out.println("For Tomcat: mvn -DskipTests package  then copy target/stocksugg.war");
        }
    }
}
