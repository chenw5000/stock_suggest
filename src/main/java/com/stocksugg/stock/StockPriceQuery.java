package com.stocksugg.stock;

import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.stocksugg.gemini.GeminiService;

import java.util.List;
import java.util.Map;

/**
 * Queries Gemini for current stock prices and returns structured JSON.
 */
public final class StockPriceQuery {

    public static final String STOCK_PRICE_PROMPT =
            "Obtain the current stock price for AAPL and GOOG";

    private final GeminiService gemini;

    public StockPriceQuery(GeminiService gemini) {
        this.gemini = gemini;
    }

    /** Sends the stock-price prompt and returns a JSON string response. */
    public String fetchCurrentPricesAsJson() {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .responseSchema(stockPriceSchema())
                .candidateCount(1)
                .build();

        return gemini.generateText(STOCK_PRICE_PROMPT, config);
    }

    private static Schema stockPriceSchema() {
        Schema stockEntry = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(Map.of(
                        "symbol", Schema.builder()
                                .type(Type.Known.STRING)
                                .description("Ticker symbol, e.g. AAPL or GOOG")
                                .build(),
                        "price", Schema.builder()
                                .type(Type.Known.NUMBER)
                                .description("Current stock price")
                                .build(),
                        "currency", Schema.builder()
                                .type(Type.Known.STRING)
                                .description("Currency code, typically USD")
                                .build(),
                        "asOf", Schema.builder()
                                .type(Type.Known.STRING)
                                .description("Timestamp or date of the quoted price")
                                .build()))
                .required(List.of("symbol", "price", "currency", "asOf"))
                .build();

        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(Map.of(
                        "stocks", Schema.builder()
                                .type(Type.Known.ARRAY)
                                .description("Current prices for the requested tickers")
                                .items(stockEntry)
                                .build()))
                .required(List.of("stocks"))
                .build();
    }
}
