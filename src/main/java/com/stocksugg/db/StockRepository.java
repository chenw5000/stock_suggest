package com.stocksugg.db;

import com.stocksugg.stock.AvoidClassifier;
import com.stocksugg.stock.BacktestDay;
import com.stocksugg.stock.StockDayView;
import com.stocksugg.stock.StockRow;
import com.stocksugg.stock.StringListCodec;
import com.stocksugg.stock.SuggestionUpdate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class StockRepository {

    private static final String SELECT_EXISTING_DATES = """
            SELECT "date" FROM stock
            WHERE ticker = ? AND "date" >= ? AND "date" <= ?
            """;

    private static final String INSERT = """
            INSERT INTO stock (
                ticker, "date", open, high, low, close,
                ma5, ma10, ma20, ma50, ma200, rsi14, chandeMmt, chalkinMF
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_RECENT = """
            SELECT ticker, "date", open, high, low, close,
                   ma5, ma10, ma20, ma50, ma200, rsi14, chandeMmt, chalkinMF
            FROM stock
            WHERE ticker = ?
            ORDER BY "date" DESC
            LIMIT ?
            """;

    private static final String SELECT_ALL_BARS = """
            SELECT ticker, "date", open, high, low, close,
                   ma5, ma10, ma20, ma50, ma200, rsi14, chandeMmt, chalkinMF
            FROM stock
            WHERE ticker = ? AND close IS NOT NULL
            ORDER BY "date"
            """;

    private static final String SELECT_BARS_ENDING_ON = """
            SELECT ticker, "date", open, high, low, close,
                   ma5, ma10, ma20, ma50, ma200, rsi14, chandeMmt, chalkinMF
            FROM stock
            WHERE ticker = ? AND "date" <= ?
            ORDER BY "date" DESC
            LIMIT ?
            """;

    private static final String SELECT_DATES_IN_RANGE = """
            SELECT "date"
            FROM stock
            WHERE ticker = ? AND "date" >= ? AND "date" <= ?
            ORDER BY "date"
            """;

    private static final String SELECT_BARS_IN_RANGE = """
            SELECT ticker, "date", open, high, low, close,
                   ma5, ma10, ma20, ma50, ma200, rsi14, chandeMmt, chalkinMF
            FROM stock
            WHERE ticker = ? AND "date" >= ? AND "date" <= ?
            ORDER BY "date"
            """;

    private static final String SELECT_BACKTEST_DAYS = """
            SELECT "date", close, rsi14, ma20, suggestedAction, confidence
            FROM stock
            WHERE ticker = ? AND "date" >= ? AND "date" <= ?
            ORDER BY "date"
            """;

    private static final String SELECT_BACKTEST_DAYS_WITH_PARAM = """
            SELECT s."date" AS "date", s.close AS close, s.rsi14 AS rsi14, s.ma20 AS ma20,
                   g.suggestedaction AS suggestedAction,
                   g.confidence AS confidence
            FROM stock s
            LEFT JOIN suggestions g ON g.stock_id = s.id AND g.param_id = ?
            WHERE s.ticker = ? AND s."date" >= ? AND s."date" <= ?
            ORDER BY s."date"
            """;

    private static final String SELECT_BY_DATE = """
            SELECT id, ticker, "date", open, high, low, close,
                   ma5, ma10, ma20, ma50, ma200, rsi14, chandeMmt, chalkinMF,
                   suggestedAction, confidence,
                   suggestedStopPrice, suggestedEntryPrice, suggestedProfitPrice,
                   thesis, risks
            FROM stock
            WHERE "date" = ?
            ORDER BY ticker
            """;

    /**
     * Market bars for a date plus versioned Gemini fields from {@code suggestions}
     * for the given {@code param_id} (LEFT JOIN — missing labels stay null).
     */
    private static final String SELECT_BY_DATE_WITH_PARAM = """
            SELECT s.id, s.ticker, s."date", s.open, s.high, s.low, s.close,
                   s.ma5, s.ma10, s.ma20, s.ma50, s.ma200, s.rsi14, s.chandeMmt, s.chalkinMF,
                   g.suggestedaction AS suggestedAction,
                   g.confidence AS confidence,
                   g.suggestedstopprice AS suggestedStopPrice,
                   g.suggestedentryprice AS suggestedEntryPrice,
                   g.suggestedprofitprice AS suggestedProfitPrice,
                   g.thesis AS thesis,
                   g.risks AS risks
            FROM stock s
            LEFT JOIN suggestions g ON g.stock_id = s.id AND g.param_id = ?
            WHERE s."date" = ?
            ORDER BY s.ticker
            """;

    /** Latest close strictly before {@code date} for each ticker that has a row on that date. */
    private static final String SELECT_PREVIOUS_CLOSES = """
            SELECT s.ticker, s.close
            FROM stock s
            INNER JOIN (
                SELECT ticker, MAX("date") AS prev_date
                FROM stock
                WHERE "date" < ?
                  AND ticker IN (SELECT ticker FROM stock WHERE "date" = ?)
                GROUP BY ticker
            ) prev ON s.ticker = prev.ticker AND s."date" = prev.prev_date
            """;

    /**
     * Most recent {@code n} trading days (ending on {@code date}) of suggestion labels for each
     * ticker that has a row on {@code date}, oldest first within each ticker.
     * {@code avoidSide} mirrors {@link AvoidClassifier#side} in SQL: HIGH (overextended / take
     * profit) or LOW (weak / broken); null for other actions or when indicators are missing.
     */
    private static final String SELECT_RECENT_ACTIONS = """
            SELECT ticker, "date", suggestedAction, avoidSide
            FROM (
                SELECT s.ticker, s."date",
                       g.suggestedaction AS suggestedAction,
                       CASE
                           WHEN g.suggestedaction IS NULL OR g.suggestedaction <> 'AVOID' THEN NULL
                           WHEN s.rsi14 >= ? OR s.close > s.ma20 * ? THEN 'HIGH'
                           WHEN s.rsi14 IS NULL AND (s.close IS NULL OR s.ma20 IS NULL) THEN NULL
                           ELSE 'LOW'
                       END AS avoidSide,
                       ROW_NUMBER() OVER (PARTITION BY s.ticker ORDER BY s."date" DESC) AS rn
                FROM stock s
                LEFT JOIN suggestions g ON g.stock_id = s.id AND g.param_id = ?
                WHERE s."date" <= ?
                  AND s.ticker IN (SELECT ticker FROM stock WHERE "date" = ?)
            ) recent
            WHERE rn <= ?
            ORDER BY ticker, "date"
            """;

    private static final String SELECT_HISTORY_PAGE = """
            SELECT id, ticker, "date", open, high, low, close,
                   ma5, ma10, ma20, ma50, ma200, rsi14, chandeMmt, chalkinMF,
                   suggestedAction, confidence,
                   suggestedStopPrice, suggestedEntryPrice, suggestedProfitPrice,
                   thesis, risks,
                   previousClose
            FROM (
                SELECT id, ticker, "date", open, high, low, close,
                       ma5, ma10, ma20, ma50, ma200, rsi14, chandeMmt, chalkinMF,
                       suggestedAction, confidence,
                       suggestedStopPrice, suggestedEntryPrice, suggestedProfitPrice,
                       thesis, risks,
                       LAG(close) OVER (ORDER BY "date") AS previousClose
                FROM stock
                WHERE ticker = ?
            ) hist
            ORDER BY "date" DESC
            LIMIT ? OFFSET ?
            """;

    /**
     * History page with suggestion fields from {@code suggestions} for a tuning param set.
     */
    private static final String SELECT_HISTORY_PAGE_WITH_PARAM = """
            SELECT id, ticker, "date", open, high, low, close,
                   ma5, ma10, ma20, ma50, ma200, rsi14, chandeMmt, chalkinMF,
                   suggestedAction, confidence,
                   suggestedStopPrice, suggestedEntryPrice, suggestedProfitPrice,
                   thesis, risks,
                   previousClose
            FROM (
                SELECT s.id, s.ticker, s."date", s.open, s.high, s.low, s.close,
                       s.ma5, s.ma10, s.ma20, s.ma50, s.ma200, s.rsi14, s.chandeMmt, s.chalkinMF,
                       g.suggestedaction AS suggestedAction,
                       g.confidence AS confidence,
                       g.suggestedstopprice AS suggestedStopPrice,
                       g.suggestedentryprice AS suggestedEntryPrice,
                       g.suggestedprofitprice AS suggestedProfitPrice,
                       g.thesis AS thesis,
                       g.risks AS risks,
                       LAG(s.close) OVER (ORDER BY s."date") AS previousClose
                FROM stock s
                LEFT JOIN suggestions g ON g.stock_id = s.id AND g.param_id = ?
                WHERE s.ticker = ?
            ) hist
            ORDER BY "date" DESC
            LIMIT ? OFFSET ?
            """;

    private static final String UPDATE_SUGGESTION = """
            UPDATE stock
            SET suggestedAction = ?,
                confidence = ?,
                suggestedStopPrice = ?,
                suggestedEntryPrice = ?,
                suggestedProfitPrice = ?,
                thesis = ?,
                risks = ?
            WHERE ticker = ? AND "date" = ?
            """;

    private static final String UPDATE_RSI14 = """
            UPDATE stock
            SET rsi14 = ?
            WHERE ticker = ? AND "date" = ?
            """;

    private final Connection connection;

    public StockRepository(Database database) {
        this.connection = database.connection();
    }

    /** Primary key of the stock row for {@code ticker} on {@code date}, if present. */
    public Optional<Long> findId(String ticker, LocalDate date) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM stock WHERE ticker = ? AND \"date\" = ?")) {
            ps.setString(1, ticker.toUpperCase());
            ps.setString(2, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong(1));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Inserts {@code rows} for dates that are not already stored for the ticker.
     * Existing rows (including suggestion fields like thesis/risks) are left unchanged.
     */
    public int replaceRange(String ticker, LocalDate from, LocalDate to, List<StockRow> rows)
            throws SQLException {
        Set<LocalDate> existing = findExistingDates(ticker, from, to);
        List<StockRow> toInsert = rows.stream()
                .filter(row -> !existing.contains(row.date()))
                .toList();
        if (toInsert.isEmpty()) {
            return 0;
        }

        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int inserted = 0;
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                for (StockRow row : toInsert) {
                    insert.setString(1, row.ticker());
                    insert.setString(2, row.date().toString());
                    setFloat(insert, 3, row.open());
                    setFloat(insert, 4, row.high());
                    setFloat(insert, 5, row.low());
                    setFloat(insert, 6, row.close());
                    setFloat(insert, 7, row.ma5());
                    setFloat(insert, 8, row.ma10());
                    setFloat(insert, 9, row.ma20());
                    setFloat(insert, 10, row.ma50());
                    setFloat(insert, 11, row.ma200());
                    setFloat(insert, 12, row.rsi14());
                    setFloat(insert, 13, row.chandeMmt());
                    setFloat(insert, 14, row.chalkinMF());
                    insert.addBatch();
                    inserted++;
                }
                insert.executeBatch();
            }
            connection.commit();
            return inserted;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previous);
        }
    }

    private Set<LocalDate> findExistingDates(String ticker, LocalDate from, LocalDate to)
            throws SQLException {
        Set<LocalDate> dates = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_EXISTING_DATES)) {
            ps.setString(1, ticker);
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    dates.add(LocalDate.parse(rs.getString(1)));
                }
            }
        }
        return dates;
    }

    /** Newest {@code limit} rows for ticker, returned oldest → newest. */
    public List<StockRow> findRecentBars(String ticker, int limit) throws SQLException {
        List<StockRow> newestFirst = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_RECENT)) {
            ps.setString(1, ticker.toUpperCase());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    newestFirst.add(readStockRow(rs));
                }
            }
        }
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** All stored bars with a close for one ticker, returned oldest → newest. */
    public List<StockRow> findAllBars(String ticker) throws SQLException {
        List<StockRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_ALL_BARS)) {
            ps.setString(1, ticker.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(readStockRow(rs));
                }
            }
        }
        return rows;
    }

    /**
     * Updates only {@code rsi14} for the supplied dates. A null value clears RSI for warm-up rows.
     */
    public int updateRsi14(String ticker, Map<LocalDate, Float> valuesByDate) throws SQLException {
        if (valuesByDate == null || valuesByDate.isEmpty()) {
            return 0;
        }

        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement update = connection.prepareStatement(UPDATE_RSI14)) {
            for (Map.Entry<LocalDate, Float> entry : valuesByDate.entrySet()) {
                setFloat(update, 1, entry.getValue());
                update.setString(2, ticker.toUpperCase());
                update.setString(3, entry.getKey().toString());
                update.addBatch();
            }
            int updated = 0;
            for (int count : update.executeBatch()) {
                if (count > 0 || count == Statement.SUCCESS_NO_INFO) {
                    updated++;
                }
            }
            connection.commit();
            return updated;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previous);
        }
    }

    /**
     * Up to {@code limit} bars for ticker with {@code "date" <= asOf}, returned oldest → newest.
     * The last bar is the row on {@code asOf} when that trading day exists.
     */
    public List<StockRow> findBarsEndingOn(String ticker, LocalDate asOf, int limit)
            throws SQLException {
        List<StockRow> newestFirst = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BARS_ENDING_ON)) {
            ps.setString(1, ticker.toUpperCase());
            ps.setString(2, asOf.toString());
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    newestFirst.add(readStockRow(rs));
                }
            }
        }
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** Trading dates stored for ticker in [{@code from}, {@code to}], ascending. */
    public List<LocalDate> findDatesInRange(String ticker, LocalDate from, LocalDate to)
            throws SQLException {
        List<LocalDate> dates = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_DATES_IN_RANGE)) {
            ps.setString(1, ticker.toUpperCase());
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    dates.add(LocalDate.parse(rs.getString(1)));
                }
            }
        }
        return dates;
    }

    /**
     * Close price + suggestedAction (with AVOID HIGH/LOW side) for ticker in
     * [{@code from}, {@code to}], ascending by date.
     * Days with a null close are omitted. Uses legacy columns on {@code stock}.
     */
    public List<BacktestDay> findBacktestDays(String ticker, LocalDate from, LocalDate to)
            throws SQLException {
        List<BacktestDay> days = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BACKTEST_DAYS)) {
            ps.setString(1, ticker.toUpperCase());
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    addBacktestDay(days, rs);
                }
            }
        }
        return days;
    }

    /**
     * Same as {@link #findBacktestDays(String, LocalDate, LocalDate)} but labels come from
     * {@code suggestions} for {@code paramId} (LEFT JOIN — missing param labels stay null).
     */
    public List<BacktestDay> findBacktestDays(
            String ticker, LocalDate from, LocalDate to, long paramId) throws SQLException {
        List<BacktestDay> days = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BACKTEST_DAYS_WITH_PARAM)) {
            ps.setLong(1, paramId);
            ps.setString(2, ticker.toUpperCase());
            ps.setString(3, from.toString());
            ps.setString(4, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    addBacktestDay(days, rs);
                }
            }
        }
        return days;
    }

    private static void addBacktestDay(List<BacktestDay> days, ResultSet rs) throws SQLException {
        Float close = getFloat(rs, "close");
        if (close == null) {
            return;
        }
        String action = rs.getString("suggestedAction");
        days.add(new BacktestDay(
                LocalDate.parse(rs.getString("date")),
                close,
                action,
                getFloat(rs, "confidence"),
                AvoidClassifier.side(action, getFloat(rs, "rsi14"), close, getFloat(rs, "ma20"))));
    }

    /** OHLC bars for ticker in [{@code from}, {@code to}], ascending by date. */
    public List<StockRow> findBarsInRange(String ticker, LocalDate from, LocalDate to)
            throws SQLException {
        List<StockRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BARS_IN_RANGE)) {
            ps.setString(1, ticker.toUpperCase());
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(readStockRow(rs));
                }
            }
        }
        return rows;
    }

    private static StockRow readStockRow(ResultSet rs) throws SQLException {
        return new StockRow(
                rs.getString("ticker"),
                LocalDate.parse(rs.getString("date")),
                getFloat(rs, "open"),
                getFloat(rs, "high"),
                getFloat(rs, "low"),
                getFloat(rs, "close"),
                getFloat(rs, "ma5"),
                getFloat(rs, "ma10"),
                getFloat(rs, "ma20"),
                getFloat(rs, "ma50"),
                getFloat(rs, "ma200"),
                getFloat(rs, "rsi14"),
                getFloat(rs, "chandeMmt"),
                getFloat(rs, "chalkinMF"));
    }

    /** All tickers for a single trading date, ordered by ticker (legacy stock columns). */
    public List<StockDayView> findByDate(LocalDate date) throws SQLException {
        List<StockDayView> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BY_DATE)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapStockDayView(rs));
                }
            }
        }
        return rows;
    }

    /**
     * Bars for {@code date} with suggestion fields from {@code suggestions} for {@code paramId}.
     */
    public List<StockDayView> findByDate(LocalDate date, long paramId) throws SQLException {
        List<StockDayView> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BY_DATE_WITH_PARAM)) {
            ps.setLong(1, paramId);
            ps.setString(2, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapStockDayView(rs));
                }
            }
        }
        return rows;
    }

    private static StockDayView mapStockDayView(ResultSet rs) throws SQLException {
        return new StockDayView(
                rs.getLong("id"),
                rs.getString("ticker"),
                LocalDate.parse(rs.getString("date")),
                getFloat(rs, "open"),
                getFloat(rs, "high"),
                getFloat(rs, "low"),
                getFloat(rs, "close"),
                getFloat(rs, "ma5"),
                getFloat(rs, "ma10"),
                getFloat(rs, "ma20"),
                getFloat(rs, "ma50"),
                getFloat(rs, "ma200"),
                getFloat(rs, "rsi14"),
                getFloat(rs, "chandeMmt"),
                getFloat(rs, "chalkinMF"),
                rs.getString("suggestedAction"),
                getFloat(rs, "confidence"),
                getFloat(rs, "suggestedStopPrice"),
                getFloat(rs, "suggestedEntryPrice"),
                getFloat(rs, "suggestedProfitPrice"),
                StringListCodec.decode(rs.getString("thesis")),
                StringListCodec.decode(rs.getString("risks")));
    }

    /**
     * Previous trading-day close for each ticker that has a row on {@code date}.
     * Keyed by ticker.
     */
    public Map<String, Float> findPreviousCloses(LocalDate date) throws SQLException {
        Map<String, Float> closes = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_PREVIOUS_CLOSES)) {
            ps.setString(1, date.toString());
            ps.setString(2, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Float close = getFloat(rs, "close");
                    if (close != null) {
                        closes.put(rs.getString("ticker"), close);
                    }
                }
            }
        }
        return closes;
    }

    /**
     * Last {@code days} trading days of {@code suggestedAction} (for {@code paramId}) up to and
     * including {@code date}, for each ticker that has a row on {@code date}. Keyed by ticker;
     * each list is oldest first with {@code date}, {@code suggestedAction} and {@code avoidSide}
     * ({@code HIGH} / {@code LOW} for AVOID, otherwise null) entries.
     */
    public Map<String, List<Map<String, Object>>> findRecentActions(
            LocalDate date, long paramId, int days) throws SQLException {
        Map<String, List<Map<String, Object>>> byTicker = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_RECENT_ACTIONS)) {
            ps.setFloat(1, AvoidClassifier.HIGH_RSI);
            ps.setFloat(2, AvoidClassifier.HIGH_MA20_STRETCH);
            ps.setLong(3, paramId);
            ps.setString(4, date.toString());
            ps.setString(5, date.toString());
            ps.setInt(6, days);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("date", rs.getString("date"));
                    entry.put("suggestedAction", rs.getString("suggestedAction"));
                    entry.put("avoidSide", rs.getString("avoidSide"));
                    byTicker.computeIfAbsent(rs.getString("ticker"), k -> new ArrayList<>()).add(entry);
                }
            }
        }
        return byTicker;
    }

    /**
     * Newest-first history page for a ticker, including previous trading-day close for change %.
     * {@code page} is 1-based. Uses legacy suggestion columns on {@code stock}.
     */
    public List<Map<String, Object>> findHistoryPage(String ticker, int page, int pageSize)
            throws SQLException {
        return findHistoryPage(ticker, page, pageSize, null);
    }

    /**
     * Newest-first history page; when {@code paramId} is non-null, suggestion fields come from
     * {@code suggestions} for that param (LEFT JOIN).
     */
    public List<Map<String, Object>> findHistoryPage(
            String ticker, int page, int pageSize, Long paramId) throws SQLException {
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
        String symbol = ticker.toUpperCase();
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = paramId == null ? SELECT_HISTORY_PAGE : SELECT_HISTORY_PAGE_WITH_PARAM;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (paramId == null) {
                ps.setString(1, symbol);
                ps.setInt(2, pageSize);
                ps.setInt(3, offset);
            } else {
                ps.setLong(1, paramId);
                ps.setString(2, symbol);
                ps.setInt(3, pageSize);
                ps.setInt(4, offset);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapHistoryRow(rs));
                }
            }
        }
        return rows;
    }

    private static Map<String, Object> mapHistoryRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("ticker", rs.getString("ticker"));
        row.put("date", rs.getString("date"));
        row.put("close", getFloat(rs, "close"));
        row.put("ma50", getFloat(rs, "ma50"));
        row.put("rsi14", getFloat(rs, "rsi14"));
        row.put("chandeMmt", getFloat(rs, "chandeMmt"));
        row.put("chalkinMF", getFloat(rs, "chalkinMF"));
        row.put("suggestedAction", rs.getString("suggestedAction"));
        row.put("confidence", getFloat(rs, "confidence"));
        row.put("suggestedStopPrice", getFloat(rs, "suggestedStopPrice"));
        row.put("suggestedEntryPrice", getFloat(rs, "suggestedEntryPrice"));
        row.put("suggestedProfitPrice", getFloat(rs, "suggestedProfitPrice"));
        row.put("thesis", StringListCodec.decode(rs.getString("thesis")));
        row.put("risks", StringListCodec.decode(rs.getString("risks")));

        Float close = getFloat(rs, "close");
        Float previousClose = getFloat(rs, "previousClose");
        Float change = null;
        Float changePct = null;
        if (close != null && previousClose != null) {
            change = close - previousClose;
            if (previousClose != 0f) {
                changePct = (change / previousClose) * 100f;
            }
        }
        row.put("previousClose", previousClose);
        row.put("change", change);
        row.put("changePct", changePct);
        return row;
    }

    public int updateSuggestion(String ticker, LocalDate date, SuggestionUpdate suggestion)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(UPDATE_SUGGESTION)) {
            ps.setString(1, suggestion.suggestedAction());
            setFloat(ps, 2, suggestion.confidence());
            setFloat(ps, 3, suggestion.suggestedStopPrice());
            setFloat(ps, 4, suggestion.suggestedEntryPrice());
            setFloat(ps, 5, suggestion.suggestedProfitPrice());
            setString(ps, 6, suggestion.thesis());
            setString(ps, 7, suggestion.risks());
            ps.setString(8, ticker.toUpperCase());
            ps.setString(9, date.toString());
            return ps.executeUpdate();
        }
    }

    public int countByTicker(String ticker) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM stock WHERE ticker = ?")) {
            ps.setString(1, ticker.toUpperCase());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Latest trading date stored for this ticker, if any. */
    public Optional<LocalDate> findLatestDate(String ticker) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT MAX(\"date\") FROM stock WHERE ticker = ?")) {
            ps.setString(1, ticker.toUpperCase());
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString(1);
                    if (value != null && !value.isBlank()) {
                        return Optional.of(LocalDate.parse(value));
                    }
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Nearest calendar date in {@code stock} strictly before ({@code direction < 0})
     * or after ({@code direction > 0}) {@code from}. Skips weekends/holidays with no rows.
     */
    public Optional<LocalDate> findAdjacentTradingDate(LocalDate from, int direction)
            throws SQLException {
        if (direction == 0) {
            throw new IllegalArgumentException("direction must be negative (prev) or positive (next)");
        }
        String sql = direction < 0
                ? "SELECT MAX(\"date\") FROM stock WHERE \"date\" < ?"
                : "SELECT MIN(\"date\") FROM stock WHERE \"date\" > ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString(1);
                    if (value != null && !value.isBlank()) {
                        return Optional.of(LocalDate.parse(value));
                    }
                }
                return Optional.empty();
            }
        }
    }

    private static Float getFloat(ResultSet rs, String column) throws SQLException {
        float value = rs.getFloat(column);
        return rs.wasNull() ? null : value;
    }

    private static void setFloat(PreparedStatement ps, int index, Float value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.FLOAT);
        } else {
            ps.setFloat(index, value);
        }
    }

    private static void setString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
