package com.stocksugg.stock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON payload Gemini will receive: recent bars + computed latest summary.
 */
public final class StockSummaryBuilder {

    public static final int DEFAULT_LOOKBACK_BARS = 40;
    public static final int DEFAULT_HORIZON_DAYS = 10;

    private StockSummaryBuilder() {}

    public static Map<String, Object> buildPackage(List<StockRow> barsOldestFirst, int horizonTradingDays) {
        if (barsOldestFirst == null || barsOldestFirst.isEmpty()) {
            throw new IllegalArgumentException("Need at least one stock bar to build a Gemini package.");
        }

        StockRow latest = barsOldestFirst.getLast();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("asOf", latest.date().toString());
        payload.put("ticker", latest.ticker());
        payload.put("horizonTradingDays", horizonTradingDays);
        payload.put("riskTolerance", "moderate");
        payload.put("latest", buildLatestSummary(barsOldestFirst));
        payload.put("bars", toBarMaps(barsOldestFirst));
        return payload;
    }

    /** Multi-ticker package: one nested market package per ticker. */
    public static Map<String, Object> buildMultiPackage(
            Map<String, List<StockRow>> barsByTicker,
            int horizonTradingDays) {
        if (barsByTicker == null || barsByTicker.isEmpty()) {
            throw new IllegalArgumentException("Need at least one ticker package.");
        }

        List<Map<String, Object>> stocks = new ArrayList<>();
        for (Map.Entry<String, List<StockRow>> entry : barsByTicker.entrySet()) {
            List<StockRow> bars = entry.getValue();
            if (bars == null || bars.isEmpty()) {
                continue;
            }
            stocks.add(buildPackage(bars, horizonTradingDays));
        }
        return wrapStocks(stocks, horizonTradingDays);
    }

    /**
     * Multi-snapshot package: each list of bars is one decision package (same ticker may repeat
     * with different asOf dates for historical backfill).
     */
    public static Map<String, Object> buildMultiSnapshotPackage(
            List<List<StockRow>> snapshotsOldestFirst,
            int horizonTradingDays) {
        if (snapshotsOldestFirst == null || snapshotsOldestFirst.isEmpty()) {
            throw new IllegalArgumentException("Need at least one snapshot package.");
        }
        List<Map<String, Object>> stocks = new ArrayList<>();
        for (List<StockRow> bars : snapshotsOldestFirst) {
            if (bars == null || bars.isEmpty()) {
                continue;
            }
            stocks.add(buildPackage(bars, horizonTradingDays));
        }
        return wrapStocks(stocks, horizonTradingDays);
    }

    private static Map<String, Object> wrapStocks(List<Map<String, Object>> stocks, int horizonTradingDays) {
        if (stocks.isEmpty()) {
            throw new IllegalArgumentException("All ticker bar lists were empty.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("horizonTradingDays", horizonTradingDays);
        payload.put("riskTolerance", "moderate");
        payload.put("stocks", stocks);
        return payload;
    }

    static Map<String, Object> buildLatestSummary(List<StockRow> barsOldestFirst) {
        StockRow latest = barsOldestFirst.getLast();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("close", latest.close());
        summary.put("ma5", latest.ma5());
        summary.put("ma10", latest.ma10());
        summary.put("ma20", latest.ma20());
        summary.put("ma50", latest.ma50());
        summary.put("ma200", latest.ma200());
        summary.put("rsi14", latest.rsi14());
        summary.put("chandeMmt", latest.chandeMmt());
        summary.put("chalkinMF", latest.chalkinMF());
        summary.put("closeVsMa20Pct", pctDiff(latest.close(), latest.ma20()));
        summary.put("closeVsMa50Pct", pctDiff(latest.close(), latest.ma50()));
        summary.put("closeVsMa200Pct", pctDiff(latest.close(), latest.ma200()));
        summary.put("return5dPct", periodReturnPct(barsOldestFirst, 5));
        summary.put("return20dPct", periodReturnPct(barsOldestFirst, 20));
        summary.put("maStack", describeMaStack(latest));
        return summary;
    }

    private static List<Map<String, Object>> toBarMaps(List<StockRow> bars) {
        List<Map<String, Object>> out = new ArrayList<>(bars.size());
        for (StockRow row : bars) {
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("date", row.date().toString());
            bar.put("open", row.open());
            bar.put("high", row.high());
            bar.put("low", row.low());
            bar.put("close", row.close());
            bar.put("ma5", row.ma5());
            bar.put("ma10", row.ma10());
            bar.put("ma20", row.ma20());
            bar.put("ma50", row.ma50());
            bar.put("ma200", row.ma200());
            bar.put("rsi14", row.rsi14());
            bar.put("chandeMmt", row.chandeMmt());
            bar.put("chalkinMF", row.chalkinMF());
            out.add(bar);
        }
        return out;
    }

    private static Float pctDiff(Float value, Float baseline) {
        if (value == null || baseline == null || baseline == 0f) {
            return null;
        }
        return ((value - baseline) / baseline) * 100f;
    }

    private static Float periodReturnPct(List<StockRow> bars, int lookback) {
        if (bars.size() <= lookback) {
            return null;
        }
        Float now = bars.getLast().close();
        Float then = bars.get(bars.size() - 1 - lookback).close();
        return pctDiff(now, then);
    }

    private static String describeMaStack(StockRow latest) {
        List<String> parts = new ArrayList<>();
        compare(parts, "ma5", latest.ma5(), "ma20", latest.ma20());
        compare(parts, "ma20", latest.ma20(), "ma50", latest.ma50());
        compare(parts, "ma50", latest.ma50(), "ma200", latest.ma200());
        return parts.isEmpty() ? "insufficient" : String.join("; ", parts);
    }

    private static void compare(List<String> parts, String aName, Float a, String bName, Float b) {
        if (a == null || b == null) {
            return;
        }
        if (a > b) {
            parts.add(aName + " > " + bName);
        } else if (a < b) {
            parts.add(aName + " < " + bName);
        } else {
            parts.add(aName + " = " + bName);
        }
    }
}
