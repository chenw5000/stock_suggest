package com.stocksugg.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.stock.StockDayView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared JSON helpers for suggestion API responses. */
public final class SuggestApi {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private SuggestApi() {}

    public static String suggestionsJson(LocalDate date) throws Exception {
        try (Database db = new Database()) {
            StockRepository repository = new StockRepository(db);
            List<StockDayView> rows = repository.findByDate(date);
            Map<String, Float> previousCloses = repository.findPreviousCloses(date);

            List<Map<String, Object>> enriched = new ArrayList<>(rows.size());
            for (StockDayView row : rows) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = MAPPER.convertValue(row, Map.class);
                Float previousClose = previousCloses.get(row.ticker());
                Float change = null;
                Float changePct = null;
                if (row.close() != null && previousClose != null) {
                    change = row.close() - previousClose;
                    if (previousClose != 0f) {
                        changePct = (change / previousClose) * 100f;
                    }
                }
                item.put("previousClose", previousClose);
                item.put("change", change);
                item.put("changePct", changePct);
                enriched.add(item);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("date", date.toString());
            body.put("count", enriched.size());
            body.put("rows", enriched);
            return MAPPER.writeValueAsString(body);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
