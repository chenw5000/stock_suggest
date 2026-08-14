package com.stocksugg.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocksugg.App;
import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.db.StrategyOptimizeRepository;
import com.stocksugg.stock.BacktestDay;
import com.stocksugg.stock.BacktestStrategy;
import com.stocksugg.stock.GeminiStockAdvisor;
import com.stocksugg.stock.StrategyOptimize;
import com.stocksugg.stock.SuggestionBacktester;
import com.stocksugg.stock.TickerList;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Runs one customized {@link BacktestStrategy} for the web UI
 * ({@code POST /api/backtest}).
 */
public final class BacktestApi {

    private BacktestApi() {}

    public static String runJson(String requestBody) throws Exception {
        JsonNode root = SuggestApi.mapper().readTree(requestBody == null ? "{}" : requestBody);

        String ticker = text(root, "ticker");
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        String symbol = ticker.trim().toUpperCase(Locale.ROOT);

        LocalDate from = parseDate(text(root, "from"), "from");
        LocalDate to = parseDate(text(root, "to"), "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }

        double cash = number(root, "cash", 10_000.0);
        if (cash <= 0) {
            throw new IllegalArgumentException("cash must be > 0");
        }

        int parts = (int) Math.round(number(root, "parts", 4));
        if (parts < 1 || parts > 20) {
            throw new IllegalArgumentException("parts must be between 1 and 20");
        }

        double buyConf = number(root, "minBuyConfidence", 0.0);
        double sellConf = number(root, "minSellConfidence", 0.0);
        if (buyConf < 0 || buyConf > 1 || sellConf < 0 || sellConf > 1) {
            throw new IllegalArgumentException("confidence thresholds must be between 0 and 1");
        }

        boolean sellOnSell = bool(root, "sellOnSell", true);
        boolean sellOnAvoid = bool(root, "sellOnAvoid", true);
        boolean sellAllOnAvoid = bool(root, "sellAllOnAvoid", false);

        BacktestStrategy.TradeIntent buyIntent = parts == 1
                ? BacktestStrategy.TradeIntent.BUY_ALL
                : BacktestStrategy.TradeIntent.BUY_PART;
        BacktestStrategy.TradeIntent sellIntent = sellOnSell
                ? (parts == 1
                        ? BacktestStrategy.TradeIntent.SELL_ALL
                        : BacktestStrategy.TradeIntent.SELL_PART)
                : BacktestStrategy.TradeIntent.NONE;
        BacktestStrategy.TradeIntent avoidIntent;
        if (sellAllOnAvoid) {
            avoidIntent = BacktestStrategy.TradeIntent.SELL_ALL;
        } else if (sellOnAvoid) {
            avoidIntent = parts == 1
                    ? BacktestStrategy.TradeIntent.SELL_ALL
                    : BacktestStrategy.TradeIntent.SELL_PART;
        } else {
            avoidIntent = BacktestStrategy.TradeIntent.NONE;
        }

        BacktestStrategy strategy = new BacktestStrategy(
                parts,
                buyConf,
                sellConf,
                buyIntent,
                sellIntent,
                BacktestStrategy.TradeIntent.NONE,
                avoidIntent);

        try (Database db = new Database()) {
            StockRepository repository = new StockRepository(db);
            List<BacktestDay> days = repository.findBacktestDays(symbol, from, to);
            if (days.isEmpty()) {
                throw new IllegalArgumentException(
                        "No rows found for " + symbol + " in " + from + " .. " + to);
            }

            SuggestionBacktester.Result result = SuggestionBacktester.run(cash, strategy, days);
            SuggestionBacktester.Result buyAndHold = SuggestionBacktester.buyAndHold(cash, days);

            long withAction = days.stream()
                    .filter(d -> d.suggestedAction() != null && !d.suggestedAction().isBlank())
                    .count();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticker", symbol);
            body.put("from", from.toString());
            body.put("to", to.toString());
            body.put("cash", cash);
            body.put("parts", parts);
            body.put("minBuyConfidence", buyConf);
            body.put("minSellConfidence", sellConf);
            body.put("sellOnSell", sellOnSell);
            body.put("sellOnAvoid", sellOnAvoid);
            body.put("sellAllOnAvoid", sellAllOnAvoid);
            body.put("strategy", strategy.toString());
            body.put("tradingDays", days.size());
            body.put("daysWithAction", withAction);
            body.put("result", resultMap(result));
            body.put("buyAndHold", resultMap(buyAndHold));
            body.put("trades", tradeMaps(result.trades()));
            return SuggestApi.mapper().writeValueAsString(body);
        }
    }

    /** Watch-list tickers for the backtest form dropdown. */
    public static String tickersJson() throws Exception {
        List<String> tickers = TickerList.loadFromAdmin();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", tickers.size());
        body.put("tickers", tickers);
        return SuggestApi.mapper().writeValueAsString(body);
    }

    /**
     * Best saved {@code strategy_optimize} row for ticker whose window matches
     * {@code from}/{@code to} within +/- 7 days (rank=1).
     */
    public static String bestStrategyJson(
            String ticker, String fromRaw, String toRaw, String paramRaw) throws Exception {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        String symbol = ticker.trim().toUpperCase(Locale.ROOT);
        LocalDate from = parseDate(fromRaw, "from");
        LocalDate to = parseDate(toRaw, "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        long paramId = 1L;
        if (paramRaw != null && !paramRaw.isBlank()) {
            try {
                paramId = Long.parseLong(paramRaw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("param must be an integer");
            }
            if (paramId < 1) {
                throw new IllegalArgumentException("param must be >= 1");
            }
        }

        try (Database db = new Database()) {
            StrategyOptimizeRepository repo = new StrategyOptimizeRepository(db);
            Optional<StrategyOptimize> match =
                    repo.findBestNearWindow(symbol, paramId, from, to, 7);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticker", symbol);
            body.put("paramId", paramId);
            body.put("requestedFrom", from.toString());
            body.put("requestedTo", to.toString());
            body.put("slackDays", 7);
            body.put("found", match.isPresent());
            if (match.isPresent()) {
                body.put("best", toMap(match.get()));
            } else {
                body.put("best", null);
            }
            return SuggestApi.mapper().writeValueAsString(body);
        }
    }

    /**
     * Runs the same strategy grid-search as CLI {@code --optimize}, saves rank=1,
     * and returns the saved row.
     */
    public static String optimizeJson(String requestBody) throws Exception {
        JsonNode root = SuggestApi.mapper().readTree(requestBody == null ? "{}" : requestBody);

        String ticker = text(root, "ticker");
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        LocalDate from = parseDate(text(root, "from"), "from");
        LocalDate to = parseDate(text(root, "to"), "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }

        double cash = number(root, "cash", 10_000.0);
        if (cash <= 0) {
            throw new IllegalArgumentException("cash must be > 0");
        }

        int topN = (int) Math.round(number(root, "top", 10));
        if (topN < 1) {
            throw new IllegalArgumentException("top must be >= 1");
        }

        long paramId = GeminiStockAdvisor.DEFAULT_PARAM_ID;
        JsonNode paramNode = root.get("param");
        if (paramNode == null || paramNode.isNull()) {
            paramNode = root.get("paramId");
        }
        if (paramNode != null && !paramNode.isNull() && !paramNode.asText().isBlank()) {
            try {
                paramId = paramNode.isNumber()
                        ? paramNode.asLong()
                        : Long.parseLong(paramNode.asText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("param must be an integer");
            }
            if (paramId < 1) {
                throw new IllegalArgumentException("param must be >= 1");
            }
        }

        StrategyOptimize saved = App.optimizeAndSave(ticker, from, to, cash, topN, paramId);
        // Re-read so id / computed_at match the DB row when available.
        try (Database db = new Database()) {
            StrategyOptimizeRepository repo = new StrategyOptimizeRepository(db);
            saved = repo.findByKey(saved.ticker(), saved.paramId(), from, to, 1).orElse(saved);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticker", saved.ticker());
        body.put("paramId", saved.paramId());
        body.put("requestedFrom", from.toString());
        body.put("requestedTo", to.toString());
        body.put("found", true);
        body.put("best", toMap(saved));
        return SuggestApi.mapper().writeValueAsString(body);
    }

    private static Map<String, Object> toMap(StrategyOptimize row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.id());
        map.put("ticker", row.ticker());
        map.put("paramId", row.paramId());
        map.put("fromDate", row.fromDate().toString());
        map.put("toDate", row.toDate().toString());
        map.put("startingCash", row.startingCash());
        map.put("parts", row.parts());
        map.put("minBuyConfidence", row.minBuyConfidence());
        map.put("minSellConfidence", row.minSellConfidence());
        map.put("onBuy", row.onBuy());
        map.put("onSell", row.onSell());
        map.put("onHold", row.onHold());
        map.put("onAvoid", row.onAvoid());
        map.put("endingEquity", row.endingEquity());
        map.put("endingCash", row.endingCash());
        map.put("endingShares", row.endingShares());
        map.put("lastClose", row.lastClose());
        map.put("returnPct", row.returnPct());
        map.put("buyCount", row.buyCount());
        map.put("sellCount", row.sellCount());
        map.put("skippedBuys", row.skippedBuys());
        map.put("buyHoldEquity", row.buyHoldEquity());
        map.put("buyHoldReturnPct", row.buyHoldReturnPct());
        map.put("rank", row.rank());
        map.put("computedAt", row.computedAt() == null ? null : row.computedAt().toString());
        return map;
    }

    private static Map<String, Object> resultMap(SuggestionBacktester.Result result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("startingCash", result.startingCash());
        map.put("endingCash", result.endingCash());
        map.put("endingShares", result.endingShares());
        map.put("endingClose", result.endingClose());
        map.put("endingEquity", result.endingEquity());
        map.put("returnPct", result.returnPct());
        map.put("buyCount", result.buyCount());
        map.put("sellCount", result.sellCount());
        map.put("skippedBuys", result.skippedBuys());
        return map;
    }

    private static List<Map<String, Object>> tradeMaps(List<SuggestionBacktester.Trade> trades) {
        List<Map<String, Object>> out = new ArrayList<>(trades.size());
        for (SuggestionBacktester.Trade trade : trades) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", trade.day().date().toString());
            row.put("event", trade.event());
            row.put("sharesDelta", trade.sharesDelta());
            row.put("price", trade.price());
            row.put("cashAfter", trade.cashAfter());
            row.put("sharesAfter", trade.sharesAfter());
            row.put("equityAfter", trade.equityAfter());
            row.put("suggestedAction", trade.day().suggestedAction());
            row.put("confidence", trade.day().confidence());
            out.add(row);
        }
        return out;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static double number(JsonNode root, String field, double defaultValue) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return defaultValue;
        }
        if (!node.isNumber() && !node.isTextual()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        try {
            return node.isNumber() ? node.asDouble() : Double.parseDouble(node.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a number");
        }
    }

    private static boolean bool(JsonNode root, String field, boolean defaultValue) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String text = node.asText().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text)) {
            return false;
        }
        throw new IllegalArgumentException(field + " must be true or false");
    }

    private static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be yyyy-MM-dd");
        }
    }
}
