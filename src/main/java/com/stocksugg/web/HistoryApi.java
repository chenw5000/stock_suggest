package com.stocksugg.web;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON helpers for ticker history API responses. */
public final class HistoryApi {

    public static final int DEFAULT_PAGE_SIZE = 10;

    private HistoryApi() {}

    public static String historyJson(String ticker, int page, int pageSize) throws Exception {
        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        if (symbol.isEmpty()) {
            throw new IllegalArgumentException("ticker is required");
        }
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1) {
            pageSize = DEFAULT_PAGE_SIZE;
        }

        try (Database db = new Database()) {
            StockRepository repository = new StockRepository(db);
            int totalCount = repository.countByTicker(symbol);
            int totalPages = totalCount == 0 ? 0 : (int) Math.ceil(totalCount / (double) pageSize);
            List<Map<String, Object>> rows = repository.findHistoryPage(symbol, page, pageSize);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticker", symbol);
            body.put("page", page);
            body.put("pageSize", pageSize);
            body.put("totalCount", totalCount);
            body.put("totalPages", totalPages);
            body.put("count", rows.size());
            body.put("rows", rows);
            return SuggestApi.mapper().writeValueAsString(body);
        }
    }
}
