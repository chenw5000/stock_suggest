package com.stocksugg.db;

import com.stocksugg.stock.BacktestDay;
import com.stocksugg.stock.StockDayView;
import com.stocksugg.stock.StockRow;
import com.stocksugg.stock.StringListCodec;
import com.stocksugg.stock.SuggestionUpdate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
                ma5, ma10, ma20, ma50, ma200, chandeMmt, chalkinMF
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_RECENT = """
            SELECT ticker, "date", open, high, low, close,
                   ma5, ma10, ma20, ma50, ma200, chandeMmt, chalkinMF
            FROM stock
            WHERE ticker = ?
            ORDER BY "date" DESC
            LIMIT ?
            """;

    private static final String SELECT_BARS_ENDING_ON = """
            SELECT ticker, "date", open, high, low, close,
                   ma5, ma10, ma20, ma50, ma200, chandeMmt, chalkinMF
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

    private static final String SELECT_BACKTEST_DAYS = """
            SELECT "date", close, suggestedAction, confidence
            FROM stock
            WHERE ticker = ? AND "date" >= ? AND "date" <= ?
            ORDER BY "date"
            """;

    private static final String SELECT_BY_DATE = """
            SELECT id, ticker, "date", open, high, low, close,
                   ma5, ma10, ma20, ma50, ma200, chandeMmt, chalkinMF,
                   suggestedAction, confidence,
                   suggestedStopPrice, suggestedEntryPrice, suggestedProfitPrice,
                   thesis, risks
            FROM stock
            WHERE "date" = ?
            ORDER BY ticker
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

    private static final String SELECT_HISTORY_PAGE = """
            SELECT id, ticker, "date", open, high, low, close,
                   ma5, ma10, ma20, ma50, ma200, chandeMmt, chalkinMF,
                   suggestedAction, confidence,
                   suggestedStopPrice, suggestedEntryPrice, suggestedProfitPrice,
                   thesis, risks,
                   previousClose
            FROM (
                SELECT id, ticker, "date", open, high, low, close,
                       ma5, ma10, ma20, ma50, ma200, chandeMmt, chalkinMF,
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

    private final Connection connection;

    public StockRepository(Database database) {
        this.connection = database.connection();
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
                    setFloat(insert, 12, row.chandeMmt());
                    setFloat(insert, 13, row.chalkinMF());
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
     * Close price + suggestedAction for ticker in [{@code from}, {@code to}], ascending by date.
     * Days with a null close are omitted.
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
                    Float close = getFloat(rs, "close");
                    if (close == null) {
                        continue;
                    }
                    days.add(new BacktestDay(
                            LocalDate.parse(rs.getString("date")),
                            close,
                            rs.getString("suggestedAction"),
                            getFloat(rs, "confidence")));
                }
            }
        }
        return days;
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
                getFloat(rs, "chandeMmt"),
                getFloat(rs, "chalkinMF"));
    }

    /** All tickers for a single trading date, ordered by ticker. */
    public List<StockDayView> findByDate(LocalDate date) throws SQLException {
        List<StockDayView> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BY_DATE)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new StockDayView(
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
                            getFloat(rs, "chandeMmt"),
                            getFloat(rs, "chalkinMF"),
                            rs.getString("suggestedAction"),
                            getFloat(rs, "confidence"),
                            getFloat(rs, "suggestedStopPrice"),
                            getFloat(rs, "suggestedEntryPrice"),
                            getFloat(rs, "suggestedProfitPrice"),
                            StringListCodec.decode(rs.getString("thesis")),
                            StringListCodec.decode(rs.getString("risks"))));
                }
            }
        }
        return rows;
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
     * Newest-first history page for a ticker, including previous trading-day close for change %.
     * {@code page} is 1-based.
     */
    public List<Map<String, Object>> findHistoryPage(String ticker, int page, int pageSize)
            throws SQLException {
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
        String symbol = ticker.toUpperCase();
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_HISTORY_PAGE)) {
            ps.setString(1, symbol);
            ps.setInt(2, pageSize);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("ticker", rs.getString("ticker"));
                    row.put("date", rs.getString("date"));
                    row.put("close", getFloat(rs, "close"));
                    row.put("ma50", getFloat(rs, "ma50"));
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
                    rows.add(row);
                }
            }
        }
        return rows;
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
