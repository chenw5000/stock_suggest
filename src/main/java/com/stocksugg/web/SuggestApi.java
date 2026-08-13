package com.stocksugg.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.db.TuningParamsRepository;
import com.stocksugg.stock.StockDayView;
import com.stocksugg.stock.TuningParams;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shared JSON helpers for suggestion API responses. */
public final class SuggestApi {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private SuggestApi() {}

    /** All tuning_params rows for the UI dropdown ({@code id} + {@code name}). */
    public static String tuningParamsJson() throws Exception {
        try (Database db = new Database()) {
            TuningParamsRepository repository = new TuningParamsRepository(db);
            List<Map<String, Object>> params = new ArrayList<>();
            for (TuningParams row : repository.findAll()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.id());
                String name = row.name();
                if (name == null || name.isBlank()) {
                    name = "id " + row.id();
                }
                item.put("name", name);
                item.put("numDatePoint", row.numDatePoint());
                params.add(item);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("count", params.size());
            body.put("params", params);
            return MAPPER.writeValueAsString(body);
        }
    }

    /**
     * Nearest date in {@code stock} before/after {@code from} ({@code direction} &lt; 0 prev, &gt; 0 next).
     */
    public static String adjacentDateJson(LocalDate from, int direction) throws Exception {
        try (Database db = new Database()) {
            StockRepository repository = new StockRepository(db);
            Optional<LocalDate> adjacent = repository.findAdjacentTradingDate(from, direction);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from", from.toString());
            body.put("dir", direction < 0 ? -1 : 1);
            body.put("date", adjacent.map(LocalDate::toString).orElse(null));
            return MAPPER.writeValueAsString(body);
        }
    }

    public static String suggestionsJson(LocalDate date) throws Exception {
        return suggestionsJson(date, 1L);
    }

    /**
     * Stock rows for {@code date} with suggestion labels from {@code suggestions}
     * for the given {@code paramId}.
     */
    public static String suggestionsJson(LocalDate date, long paramId) throws Exception {
        try (Database db = new Database()) {
            StockRepository repository = new StockRepository(db);
            List<StockDayView> rows = repository.findByDate(date, paramId);
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
            body.put("paramId", paramId);
            body.put("count", enriched.size());
            body.put("rows", enriched);
            return MAPPER.writeValueAsString(body);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
