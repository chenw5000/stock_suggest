package com.stocksugg.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocksugg.db.Database;
import com.stocksugg.db.PerformanceRepository;
import com.stocksugg.stock.BacktestStrategy;
import com.stocksugg.stock.GeminiStockAdvisor;
import com.stocksugg.stock.PerformanceRunner;
import com.stocksugg.stock.PerformanceTickerResult;
import com.stocksugg.stock.PerformanceWeek;
import com.stocksugg.stock.WeeklyPerformance;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Weekly performance snapshots for {@code performance.html} ({@code /api/performance/*}). */
public final class PerformanceApi {

    /** HTTP status plus JSON body for the endpoints that start background jobs. */
    public record Response(int status, String json) {}

    private PerformanceApi() {}

    /** All saved weeks for the param, oldest first (charts + week picker). */
    public static String weeksJson(String paramRaw) throws Exception {
        long paramId = parseParamId(paramRaw);
        try (Database db = new Database()) {
            List<Map<String, Object>> weeks = new ArrayList<>();
            for (PerformanceWeek week : new PerformanceRepository(db).listWeeks(paramId)) {
                weeks.add(weekMap(week));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("paramId", paramId);
            body.put("latestCompleteWeek", PerformanceRunner.latestWeekEnding().toString());
            body.put("weeks", weeks);
            return SuggestApi.mapper().writeValueAsString(body);
        }
    }

    /** One week's summary and ticker rows; the latest saved week when {@code weekRaw} is blank. */
    public static String weekJson(String weekRaw, String paramRaw) throws Exception {
        long paramId = parseParamId(paramRaw);
        try (Database db = new Database()) {
            PerformanceRepository repo = new PerformanceRepository(db);
            Optional<PerformanceWeek> week = weekRaw == null || weekRaw.isBlank()
                    ? repo.findLatestWeek(paramId)
                    : repo.findWeek(WeeklyPerformance.weekEnding(parseDate(weekRaw, "week")), paramId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("paramId", paramId);
            body.put("found", week.isPresent());
            if (week.isPresent()) {
                body.put("week", weekMap(week.get()));
                List<Map<String, Object>> rows = new ArrayList<>();
                for (PerformanceTickerResult row : repo.findTickers(week.get().id())) {
                    rows.add(tickerMap(row));
                }
                body.put("rows", rows);
            }
            return SuggestApi.mapper().writeValueAsString(body);
        }
    }

    public static String statusJson() throws Exception {
        return SuggestApi.mapper().writeValueAsString(statusMap(PerformanceRunner.status()));
    }

    /** {@code {week?, recompute?, param?}}: evaluates one week in the background. */
    public static Response runJson(String requestBody) throws Exception {
        JsonNode root = SuggestApi.mapper().readTree(requestBody == null ? "{}" : requestBody);
        String weekRaw = text(root, "week");
        LocalDate week = weekRaw == null || weekRaw.isBlank() ? null : parseDate(weekRaw, "week");
        long paramId = parseParamId(text(root, "param"));
        boolean recompute = root.path("recompute").asBoolean(false);
        return started(PerformanceRunner.startWeek(week, paramId, recompute));
    }

    /** {@code {weeks, recompute?, param?}}: fills missing past weeks in the background. */
    public static Response backfillJson(String requestBody) throws Exception {
        JsonNode root = SuggestApi.mapper().readTree(requestBody == null ? "{}" : requestBody);
        int weeks = root.path("weeks").asInt(12);
        long paramId = parseParamId(text(root, "param"));
        boolean recompute = root.path("recompute").asBoolean(false);
        return started(PerformanceRunner.startBackfill(weeks, paramId, recompute));
    }

    private static Response started(PerformanceRunner.StartResult result) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("started", result == PerformanceRunner.StartResult.STARTED);
        body.put("message", switch (result) {
            case STARTED -> "Started.";
            case ALREADY_RUNNING -> "A weekly performance job is already running.";
            case BATCH_RUNNING -> "The daily batch is running; try again when it finishes.";
        });
        body.put("status", statusMap(PerformanceRunner.status()));
        int http = result == PerformanceRunner.StartResult.STARTED ? 202 : 409;
        return new Response(http, SuggestApi.mapper().writeValueAsString(body));
    }

    private static Map<String, Object> weekMap(PerformanceWeek week) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("weekEnding", week.weekEnding().toString());
        map.put("asOfDate", week.asOfDate().toString());
        map.put("trainFrom", week.trainFrom().toString());
        map.put("trainTo", week.trainTo().toString());
        map.put("testFrom", week.testFrom().toString());
        map.put("testTo", week.testTo().toString());
        map.put("trigger", week.triggerSource());
        map.put("tickersTotal", week.tickersTotal());
        map.put("evaluated", week.evaluated());
        map.put("skipped", week.skipped());
        map.put("beatCount", week.beatCount());
        map.put("notBeatCount", week.notBeatCount());
        map.put("invested", week.invested());
        map.put("strategyTotal", week.strategyTotal());
        map.put("buyHoldTotal", week.buyHoldTotal());
        map.put("strategyReturnPct", week.strategyReturnPct());
        map.put("buyHoldReturnPct", week.buyHoldReturnPct());
        map.put("durationMs", week.durationMs());
        map.put("computedAt", week.computedAt() == null ? null : week.computedAt().toString());
        return map;
    }

    private static Map<String, Object> tickerMap(PerformanceTickerResult row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ticker", row.ticker());
        map.put("status", row.status());
        map.put("skipReason", row.skipReason());
        BacktestStrategy strategy = row.strategy();
        if (strategy != null) {
            map.put("parts", strategy.parts());
            map.put("minBuyConfidence", strategy.minBuyConfidence());
            map.put("minSellConfidence", strategy.minSellConfidence());
            map.put("onBuy", strategy.onBuy().name());
            map.put("onSell", strategy.onSell().name());
            map.put("onAvoidHigh", strategy.onAvoidHigh().name());
            map.put("onAvoidLow", strategy.onAvoidLow().name());
        }
        map.put("strategyCached", row.strategyCached());
        map.put("trainReturnPct", row.trainReturnPct());
        map.put("trainBuyHoldReturnPct", row.trainBuyHoldReturnPct());
        map.put("testTradingDays", row.testTradingDays());
        map.put("strategyEquity", row.strategyEquity());
        map.put("strategyReturnPct", row.strategyReturnPct());
        map.put("buyCount", row.buyCount());
        map.put("sellCount", row.sellCount());
        map.put("buyHoldEquity", row.buyHoldEquity());
        map.put("buyHoldReturnPct", row.buyHoldReturnPct());
        map.put("beat", row.beat());
        return map;
    }

    private static Map<String, Object> statusMap(PerformanceRunner.Status status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("running", status.running());
        map.put("job", status.job());
        map.put("trigger", status.trigger());
        map.put("weekEnding", status.weekEnding() == null ? null : status.weekEnding().toString());
        map.put("weekIndex", status.weekIndex());
        map.put("weekTotal", status.weekTotal());
        map.put("tickerIndex", status.tickerIndex());
        map.put("tickerTotal", status.tickerTotal());
        map.put("ticker", status.ticker());
        map.put("startedAt", status.startedAt() == null ? null : status.startedAt().toString());
        map.put("finishedAt", status.finishedAt() == null ? null : status.finishedAt().toString());
        map.put("message", status.message());
        map.put("error", status.error());
        return map;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static long parseParamId(String raw) {
        if (raw == null || raw.isBlank()) {
            return GeminiStockAdvisor.DEFAULT_PARAM_ID;
        }
        try {
            long paramId = Long.parseLong(raw.trim());
            if (paramId < 1) {
                throw new IllegalArgumentException("param must be >= 1");
            }
            return paramId;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("param must be an integer");
        }
    }

    private static LocalDate parseDate(String raw, String field) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be yyyy-MM-dd");
        }
    }
}
