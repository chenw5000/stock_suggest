package com.stocksugg.web;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.stock.StockRow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON helpers for the close-price chart API ({@code /api/chart/{ticker}?months=6}). */
public final class ChartApi {

    public static final int DEFAULT_MONTHS = 6;

    private ChartApi() {}

    /** Daily OHLC bars for the last {@code months} calendar months, oldest first. */
    public static String closesJson(String ticker, int months) throws Exception {
        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        if (symbol.isEmpty()) {
            throw new IllegalArgumentException("ticker is required");
        }
        if (months < 1) {
            months = DEFAULT_MONTHS;
        }
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusMonths(months);

        try (Database db = new Database()) {
            StockRepository repository = new StockRepository(db);
            List<StockRow> bars = repository.findBarsInRange(symbol, from, to);

            List<Map<String, Object>> points = new ArrayList<>(bars.size());
            for (StockRow bar : bars) {
                if (bar.open() == null || bar.high() == null
                        || bar.low() == null || bar.close() == null) {
                    continue;
                }
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("date", bar.date().toString());
                point.put("open", bar.open());
                point.put("high", bar.high());
                point.put("low", bar.low());
                point.put("close", bar.close());
                points.add(point);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticker", symbol);
            body.put("from", from.toString());
            body.put("to", to.toString());
            body.put("count", points.size());
            body.put("points", points);
            return SuggestApi.mapper().writeValueAsString(body);
        }
    }
}
