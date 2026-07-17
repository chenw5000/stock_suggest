package com.stocksugg.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.stocksugg.db.StockRepository;
import com.stocksugg.gemini.GeminiService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads recent bars from the database, asks Gemini for structured suggestion(s), prints and persists them.
 */
public final class GeminiStockAdvisor {

    /** Max historical as-of packages per Gemini call (keeps prompt size manageable). */
    public static final int DEFAULT_HISTORICAL_CHUNK_SIZE = 4;

    public static final String SYSTEM_INSTRUCTION = """
            Use only provided OHLCV/indicators. No invented quotes. Return JSON schema.
            If data is insufficient for a package, action=AVOID and explain for that package.
            Price ranges must be grounded in the supplied prices/levels (entryPriceRange,
            cutlossPriceRange, profitTakingPriceRange). confidence is 0.0-1.0.
            When multiple stock packages are provided, return exactly one suggestion object
            per package in the suggestions array. Each package has ticker + asOf — copy that
            asOf into the suggestion, keep packages independent, and do not use later data.
            """;

    private final StockRepository repository;
    private final GeminiService gemini;
    private final ObjectMapper objectMapper;

    public GeminiStockAdvisor(StockRepository repository, GeminiService gemini) {
        this.repository = repository;
        this.gemini = gemini;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public GeminiSuggestion advise(String ticker) throws Exception {
        List<GeminiSuggestion> results = adviseMany(List.of(ticker));
        if (results.isEmpty()) {
            throw new IllegalStateException("Gemini returned no suggestion for " + ticker);
        }
        return results.getFirst();
    }

    public List<GeminiSuggestion> adviseMany(List<String> tickers) throws Exception {
        return adviseMany(tickers, StockSummaryBuilder.DEFAULT_LOOKBACK_BARS,
                StockSummaryBuilder.DEFAULT_HORIZON_DAYS);
    }

    public List<GeminiSuggestion> adviseMany(
            List<String> tickers, int lookbackBars, int horizonTradingDays) throws Exception {
        if (tickers == null || tickers.isEmpty()) {
            throw new IllegalArgumentException("At least one ticker is required.");
        }

        List<AdviceSnapshot> snapshots = new ArrayList<>();
        for (String raw : tickers) {
            String ticker = raw.trim().toUpperCase(Locale.ROOT);
            List<StockRow> bars = repository.findRecentBars(ticker, lookbackBars);
            if (bars.isEmpty()) {
                System.out.println("Skipping " + ticker + ": no rows in database.");
                continue;
            }
            snapshots.add(new AdviceSnapshot(ticker, bars.getLast().date(), bars));
        }
        if (snapshots.isEmpty()) {
            throw new IllegalStateException("No stock rows found for any requested ticker.");
        }

        return requestAndPersist(snapshots, horizonTradingDays).persisted();
    }

    /**
     * Asks Gemini for a suggestion as of a historical trading day and updates that row.
     * Uses lookback bars ending on {@code asOf} (must already exist in the database).
     */
    public GeminiSuggestion adviseAsOf(String ticker, LocalDate asOf) throws Exception {
        List<GeminiSuggestion> results = adviseAsOfMany(ticker, List.of(asOf));
        if (results.isEmpty()) {
            throw new IllegalStateException("Gemini returned no suggestion for " + ticker + " on " + asOf);
        }
        return results.getFirst();
    }

    /**
     * Batch historical advice: one Gemini API call per chunk of as-of dates for the same ticker.
     */
    public List<GeminiSuggestion> adviseAsOfMany(String ticker, List<LocalDate> asOfDates)
            throws Exception {
        return adviseAsOfMany(
                ticker,
                asOfDates,
                StockSummaryBuilder.DEFAULT_LOOKBACK_BARS,
                StockSummaryBuilder.DEFAULT_HORIZON_DAYS,
                DEFAULT_HISTORICAL_CHUNK_SIZE);
    }

    public List<GeminiSuggestion> adviseAsOfMany(
            String ticker,
            List<LocalDate> asOfDates,
            int lookbackBars,
            int horizonTradingDays,
            int chunkSize) throws Exception {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required.");
        }
        if (asOfDates == null || asOfDates.isEmpty()) {
            throw new IllegalArgumentException("at least one asOf date is required.");
        }
        if (chunkSize < 1) {
            throw new IllegalArgumentException("chunkSize must be >= 1");
        }

        String symbol = ticker.trim().toUpperCase(Locale.ROOT);
        List<AdviceSnapshot> snapshots = new ArrayList<>();
        for (LocalDate asOf : asOfDates) {
            if (asOf == null) {
                continue;
            }
            List<StockRow> bars = repository.findBarsEndingOn(symbol, asOf, lookbackBars);
            if (bars.isEmpty()) {
                System.out.println("Skipping " + symbol + " on " + asOf + ": no bars.");
                continue;
            }
            LocalDate lastBarDate = bars.getLast().date();
            if (!lastBarDate.equals(asOf)) {
                System.out.println("Skipping " + symbol + " on " + asOf
                        + ": no row that day (latest on/before is " + lastBarDate + ")");
                continue;
            }
            snapshots.add(new AdviceSnapshot(symbol, asOf, bars));
        }
        if (snapshots.isEmpty()) {
            throw new IllegalStateException("No usable snapshots for " + symbol);
        }

        List<GeminiSuggestion> all = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i += chunkSize) {
            List<AdviceSnapshot> chunk = new ArrayList<>(
                    snapshots.subList(i, Math.min(i + chunkSize, snapshots.size())));
            all.addAll(requestChunkWithRetry(chunk, horizonTradingDays));
        }
        return all;
    }

    /**
     * Sends a chunk to Gemini; any omitted asOf dates are retried one-by-one.
     */
    private List<GeminiSuggestion> requestChunkWithRetry(
            List<AdviceSnapshot> chunk, int horizonTradingDays) throws Exception {
        System.out.println("Gemini historical batch for " + chunk.getFirst().ticker()
                + ": " + chunk.size() + " day(s) ("
                + chunk.getFirst().asOf() + " .. " + chunk.getLast().asOf() + ")");
        PersistResult first = requestAndPersist(chunk, horizonTradingDays);
        List<GeminiSuggestion> all = new ArrayList<>(first.persisted());

        List<AdviceSnapshot> omitted = chunk.stream()
                .filter(s -> first.omittedKeys().contains(snapshotKey(s.ticker(), s.asOf())))
                .toList();
        if (omitted.isEmpty()) {
            return all;
        }

        System.out.println("Retrying " + omitted.size()
                + " omitted day(s) one at a time (Gemini often drops packages in large batches)...");
        for (AdviceSnapshot snapshot : omitted) {
            System.out.println("Retry Gemini for " + snapshot.ticker() + " on " + snapshot.asOf());
            PersistResult retry = requestAndPersist(List.of(snapshot), horizonTradingDays);
            all.addAll(retry.persisted());
            if (!retry.omittedKeys().isEmpty()) {
                System.err.println("Still missing after retry: " + snapshot.ticker()
                        + " on " + snapshot.asOf());
            }
        }
        return all;
    }

    private PersistResult requestAndPersist(
            List<AdviceSnapshot> snapshots,
            int horizonTradingDays) throws Exception {
        List<List<StockRow>> barLists = snapshots.stream().map(AdviceSnapshot::bars).toList();
        Map<String, Object> payload =
                StockSummaryBuilder.buildMultiSnapshotPackage(barLists, horizonTradingDays);

        Set<String> expectedKeys = new HashSet<>();
        List<String> asOfList = new ArrayList<>();
        for (AdviceSnapshot snapshot : snapshots) {
            expectedKeys.add(snapshotKey(snapshot.ticker(), snapshot.asOf()));
            asOfList.add(snapshot.asOf().toString());
        }

        String userPrompt = """
                Analyze this multi-stock market package and return one suggestion per stock package.
                You MUST return exactly %d suggestion(s), one for each asOf date: %s.
                Each package has its own ticker and asOf date — treat that asOf as the decision date
                (do not use later information), and return the same asOf in each suggestion.
                Do not omit any asOf date from the list.
                Package:
                %s
                """.formatted(
                snapshots.size(),
                String.join(", ", asOfList),
                objectMapper.writeValueAsString(payload));

        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(SYSTEM_INSTRUCTION)))
                .responseMimeType("application/json")
                .responseSchema(batchSuggestionSchema())
                .candidateCount(1)
                .build();

        String json = gemini.generateText(userPrompt, config);
        GeminiSuggestionBatch batch = objectMapper.readValue(json, GeminiSuggestionBatch.class);
        if (batch.suggestions() == null || batch.suggestions().isEmpty()) {
            throw new IllegalStateException("Gemini returned an empty suggestions array.");
        }

        List<GeminiSuggestion> persisted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (GeminiSuggestion suggestion : batch.suggestions()) {
            String ticker = suggestion.ticker() == null
                    ? null
                    : suggestion.ticker().trim().toUpperCase(Locale.ROOT);
            if (ticker == null) {
                System.out.println("Skipping suggestion with missing ticker.");
                continue;
            }

            LocalDate asOf;
            try {
                asOf = resolveAsOf(suggestion, null);
            } catch (Exception e) {
                System.out.println("Skipping suggestion with invalid asOf for " + ticker + ": "
                        + suggestion.asOf());
                continue;
            }
            if (asOf == null) {
                // Single-snapshot fallback only.
                if (snapshots.size() == 1 && snapshots.getFirst().ticker().equals(ticker)) {
                    asOf = snapshots.getFirst().asOf();
                } else {
                    System.out.println("Skipping " + ticker + ": missing asOf in multi-package response.");
                    continue;
                }
            }

            String key = snapshotKey(ticker, asOf);
            if (!expectedKeys.contains(key)) {
                System.out.println("Skipping unexpected suggestion: " + ticker + " / " + asOf);
                continue;
            }
            if (!seen.add(key)) {
                System.out.println("Skipping duplicate suggestion: " + ticker + " / " + asOf);
                continue;
            }

            int updated = repository.updateSuggestion(ticker, asOf, suggestion.toDbUpdate());
            if (updated == 0) {
                throw new IllegalStateException(
                        "Failed to update suggestion for " + ticker + " on " + asOf);
            }
            persisted.add(suggestion);
        }

        Set<String> missing = new HashSet<>(expectedKeys);
        missing.removeAll(seen);
        if (!missing.isEmpty()) {
            System.out.println("Gemini omitted " + missing.size()
                    + " package(s) in this batch: " + missing);
        }
        return new PersistResult(persisted, missing);
    }

    public static void printSuggestion(GeminiSuggestion suggestion) {
        System.out.println("--- Gemini suggestion ---");
        System.out.println("ticker: " + suggestion.ticker());
        System.out.println("asOf: " + suggestion.asOf());
        System.out.println("action: " + suggestion.action());
        System.out.println("confidence: " + suggestion.confidence());
        System.out.println("entryPriceRange: " + suggestion.entryPriceRange());
        System.out.println("cutlossPriceRange: " + suggestion.cutlossPriceRange());
        System.out.println("profitTakingPriceRange: " + suggestion.profitTakingPriceRange());
        System.out.println("horizonTradingDays: " + suggestion.horizonTradingDays());
        System.out.println("thesis: " + suggestion.thesis());
        System.out.println("keyLevels: " + suggestion.keyLevels());
        System.out.println("risks: " + suggestion.risks());
        System.out.println("invalidation: " + suggestion.invalidation());
        System.out.println("dataUsed: " + suggestion.dataUsed());
        SuggestionUpdate db = suggestion.toDbUpdate();
        System.out.println("DB mapped prices (midpoints): stop=" + db.suggestedStopPrice()
                + ", entry=" + db.suggestedEntryPrice()
                + ", profit=" + db.suggestedProfitPrice());
        System.out.println("-------------------------");
    }

    private static LocalDate resolveAsOf(GeminiSuggestion suggestion, LocalDate fallback) {
        if (suggestion.asOf() == null || suggestion.asOf().isBlank()) {
            return fallback;
        }
        return LocalDate.parse(suggestion.asOf());
    }

    private static String snapshotKey(String ticker, LocalDate asOf) {
        return ticker + "|" + asOf;
    }

    private record AdviceSnapshot(String ticker, LocalDate asOf, List<StockRow> bars) {}

    private record PersistResult(List<GeminiSuggestion> persisted, Set<String> omittedKeys) {}

    private static Schema batchSuggestionSchema() {
        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(Map.of(
                        "suggestions", Schema.builder()
                                .type(Type.Known.ARRAY)
                                .items(suggestionItemSchema())
                                .build()))
                .required(List.of("suggestions"))
                .build();
    }

    private static Schema suggestionItemSchema() {
        Schema stringArray = Schema.builder()
                .type(Type.Known.ARRAY)
                .items(Schema.builder().type(Type.Known.STRING).build())
                .build();

        Schema keyLevels = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(Map.of(
                        "support", Schema.builder().type(Type.Known.NUMBER).build(),
                        "resistance", Schema.builder().type(Type.Known.NUMBER).build()))
                .required(List.of("support", "resistance"))
                .build();

        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(Map.ofEntries(
                        Map.entry("ticker", Schema.builder().type(Type.Known.STRING).build()),
                        Map.entry("asOf", Schema.builder().type(Type.Known.STRING).build()),
                        Map.entry("action", Schema.builder()
                                .type(Type.Known.STRING)
                                .description("One of BUY, HOLD, SELL, AVOID")
                                .build()),
                        Map.entry("confidence", Schema.builder().type(Type.Known.NUMBER).build()),
                        Map.entry("entryPriceRange", Schema.builder()
                                .type(Type.Known.STRING)
                                .description("e.g. 300 - 310")
                                .build()),
                        Map.entry("cutlossPriceRange", Schema.builder()
                                .type(Type.Known.STRING)
                                .description("e.g. 290 - 292")
                                .build()),
                        Map.entry("profitTakingPriceRange", Schema.builder()
                                .type(Type.Known.STRING)
                                .description("e.g. 340 - 345")
                                .build()),
                        Map.entry("horizonTradingDays", Schema.builder().type(Type.Known.INTEGER).build()),
                        Map.entry("thesis", stringArray),
                        Map.entry("keyLevels", keyLevels),
                        Map.entry("risks", stringArray),
                        Map.entry("invalidation", Schema.builder().type(Type.Known.STRING).build()),
                        Map.entry("dataUsed", stringArray)))
                .required(List.of(
                        "ticker", "asOf", "action", "confidence",
                        "entryPriceRange", "cutlossPriceRange", "profitTakingPriceRange",
                        "horizonTradingDays", "thesis", "keyLevels", "risks",
                        "invalidation", "dataUsed"))
                .build();
    }
}
