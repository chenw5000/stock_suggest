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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads recent bars from the database, asks Gemini for structured suggestion(s), prints and persists them.
 */
public final class GeminiStockAdvisor {

    public static final String SYSTEM_INSTRUCTION = """
            Use only provided OHLCV/indicators. No invented quotes. Return JSON schema.
            If data is insufficient for a ticker, action=AVOID and explain for that ticker.
            Price ranges must be grounded in the supplied prices/levels (entryPriceRange,
            cutlossPriceRange, profitTakingPriceRange). confidence is 0.0-1.0.
            When multiple stocks are provided, return one suggestion object per ticker in
            the suggestions array. Keep each suggestion independent — do not mix tickers.
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

        Map<String, List<StockRow>> barsByTicker = new LinkedHashMap<>();
        Map<String, LocalDate> latestDateByTicker = new LinkedHashMap<>();
        for (String raw : tickers) {
            String ticker = raw.trim().toUpperCase(Locale.ROOT);
            List<StockRow> bars = repository.findRecentBars(ticker, lookbackBars);
            if (bars.isEmpty()) {
                System.out.println("Skipping " + ticker + ": no rows in database.");
                continue;
            }
            barsByTicker.put(ticker, bars);
            latestDateByTicker.put(ticker, bars.getLast().date());
        }
        if (barsByTicker.isEmpty()) {
            throw new IllegalStateException("No stock rows found for any requested ticker.");
        }

        Map<String, Object> payload = StockSummaryBuilder.buildMultiPackage(barsByTicker, horizonTradingDays);
        String userPrompt = """
                Analyze this multi-stock market package and return one suggestion per ticker.
                Package:
                %s
                """.formatted(objectMapper.writeValueAsString(payload));

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
        for (GeminiSuggestion suggestion : batch.suggestions()) {
            String ticker = suggestion.ticker() == null
                    ? null
                    : suggestion.ticker().trim().toUpperCase(Locale.ROOT);
            if (ticker == null || !latestDateByTicker.containsKey(ticker)) {
                System.out.println("Skipping unexpected suggestion ticker: " + suggestion.ticker());
                continue;
            }
            LocalDate asOf = resolveAsOf(suggestion, latestDateByTicker.get(ticker));
            int updated = repository.updateSuggestion(ticker, asOf, suggestion.toDbUpdate());
            if (updated == 0) {
                asOf = latestDateByTicker.get(ticker);
                updated = repository.updateSuggestion(ticker, asOf, suggestion.toDbUpdate());
            }
            if (updated == 0) {
                throw new IllegalStateException(
                        "Failed to update suggestion for " + ticker + " on " + asOf);
            }
            persisted.add(suggestion);
        }
        return persisted;
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
