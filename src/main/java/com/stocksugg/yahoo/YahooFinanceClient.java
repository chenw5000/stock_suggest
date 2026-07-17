package com.stocksugg.yahoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksugg.stock.StockBar;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads daily OHLCV bars from Yahoo Finance chart API (no API key).
 */
public final class YahooFinanceClient {

    private static final String CHART_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s"
                    + "?period1=%d&period2=%d&interval=1d&includePrePost=false&events=div%%7Csplit";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public YahooFinanceClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public List<StockBar> downloadDailyBars(String ticker, LocalDate startInclusive, LocalDate endExclusive)
            throws IOException, InterruptedException {
        long period1 = startInclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long period2 = endExclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String url = CHART_URL.formatted(ticker.toUpperCase(), period1, period2);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Yahoo Finance HTTP " + response.statusCode() + " for " + ticker);
        }
        return parseChart(ticker.toUpperCase(), response.body());
    }

    private List<StockBar> parseChart(String ticker, String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode result = root.path("chart").path("result");
        if (!result.isArray() || result.isEmpty()) {
            String err = root.path("chart").path("error").toString();
            throw new IOException("Yahoo Finance returned no data for " + ticker + ": " + err);
        }

        JsonNode first = result.get(0);
        JsonNode timestamps = first.path("timestamp");
        JsonNode quote = first.path("indicators").path("quote").path(0);
        JsonNode opens = quote.path("open");
        JsonNode highs = quote.path("high");
        JsonNode lows = quote.path("low");
        JsonNode closes = quote.path("close");
        JsonNode volumes = quote.path("volume");

        if (!timestamps.isArray() || timestamps.isEmpty()) {
            throw new IOException("Yahoo Finance chart missing timestamps for " + ticker);
        }

        List<StockBar> bars = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            if (opens.get(i).isNull() || highs.get(i).isNull()
                    || lows.get(i).isNull() || closes.get(i).isNull()) {
                continue;
            }
            LocalDate date = Instant.ofEpochSecond(timestamps.get(i).asLong())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();
            long volume = volumes.get(i).isNull() ? 0L : volumes.get(i).asLong();
            bars.add(new StockBar(
                    ticker,
                    date,
                    opens.get(i).asDouble(),
                    highs.get(i).asDouble(),
                    lows.get(i).asDouble(),
                    closes.get(i).asDouble(),
                    volume));
        }
        return bars;
    }
}
