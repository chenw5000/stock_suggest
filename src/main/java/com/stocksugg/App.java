package com.stocksugg;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.gemini.GeminiConfig;
import com.stocksugg.gemini.GeminiService;
import com.stocksugg.stock.GeminiStockAdvisor;
import com.stocksugg.stock.GeminiSuggestion;
import com.stocksugg.stock.MarketSession;
import com.stocksugg.stock.StockDataImporter;
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

    private static void runBatchJob() {
        List<String> tickers = List.of(
                "AAPL", "TSLA", "MSFT", "NVDA", "GOOGL", "MU", "SOXX", "ARKG", "ARKK", "NOK");
        for (String ticker : tickers) {
            refreshStockData(ticker);
        }
        adviseStocks(tickers);
    }

    public static void main(String[] args) {
        System.out.println("StockSugg starting...");

        List<String> argList = Arrays.asList(args);
        boolean runBatch = argList.contains("--batch");
        boolean embedded = argList.contains("--embedded");

        if (runBatch) {
            runBatchJob();
        }

        if (embedded) {
            // Local/dev only. Prefer Tomcat WAR deployment for the standard web app.
            WebServer.start(WebServer.DEFAULT_PORT);
            System.out.println("Embedded Javalin ready. Press Ctrl+C to stop.");
            return;
        }

        if (!runBatch) {
            System.out.println("Nothing to run. Options:");
            System.out.println("  --batch              refresh Yahoo data + Gemini suggestions");
            System.out.println("  --embedded           start embedded Javalin on port 7070 (dev)");
            System.out.println("  --batch --embedded   batch then start embedded server");
            System.out.println("For Tomcat: mvn -DskipTests package  then copy target/stocksugg.war");
        }
    }
}
