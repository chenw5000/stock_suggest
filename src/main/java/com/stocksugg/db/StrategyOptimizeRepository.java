package com.stocksugg.db;

import com.stocksugg.stock.StrategyOptimize;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Persistence for {@code strategy_optimize} search results
 * (best / top-N strategies per ticker window and param_id).
 */
public final class StrategyOptimizeRepository {

    private static final String INSERT = """
            INSERT INTO strategy_optimize (
                ticker, param_id, from_date, to_date, starting_cash,
                parts, min_buy_confidence, min_sell_confidence,
                on_buy, on_sell, on_hold, on_avoid, on_avoid_high, on_avoid_low,
                ending_equity, ending_cash, ending_shares, last_close, return_pct,
                buy_count, sell_count, skipped_buys,
                buy_hold_equity, buy_hold_return_pct, rank
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?
            )
            """;

    private static final String UPDATE = """
            UPDATE strategy_optimize
            SET starting_cash = ?,
                parts = ?,
                min_buy_confidence = ?,
                min_sell_confidence = ?,
                on_buy = ?,
                on_sell = ?,
                on_hold = ?,
                on_avoid = ?,
                on_avoid_high = ?,
                on_avoid_low = ?,
                ending_equity = ?,
                ending_cash = ?,
                ending_shares = ?,
                last_close = ?,
                return_pct = ?,
                buy_count = ?,
                sell_count = ?,
                skipped_buys = ?,
                buy_hold_equity = ?,
                buy_hold_return_pct = ?,
                computed_at = CURRENT_TIMESTAMP
            WHERE ticker = ? AND param_id = ? AND from_date = ? AND to_date = ? AND rank = ?
            """;

    private static final String SELECT_COLUMNS = """
            id, ticker, param_id, from_date, to_date, starting_cash,
            parts, min_buy_confidence, min_sell_confidence,
            on_buy, on_sell, on_hold,
            COALESCE(on_avoid_high, on_avoid) AS on_avoid_high,
            COALESCE(on_avoid_low, on_avoid) AS on_avoid_low,
            ending_equity, ending_cash, ending_shares, last_close, return_pct,
            buy_count, sell_count, skipped_buys,
            buy_hold_equity, buy_hold_return_pct, rank, computed_at
            """;

    private static final String DELETE_BY_WINDOW = """
            DELETE FROM strategy_optimize
            WHERE ticker = ? AND param_id = ? AND from_date = ? AND to_date = ?
            """;

    private static final String SELECT_BY_KEY =
            "SELECT " + SELECT_COLUMNS
                    + "FROM strategy_optimize "
                    + "WHERE ticker = ? AND param_id = ? AND from_date = ? AND to_date = ? AND rank = ?";

    private static final String SELECT_BY_WINDOW =
            "SELECT " + SELECT_COLUMNS
                    + "FROM strategy_optimize "
                    + "WHERE ticker = ? AND param_id = ? AND from_date = ? AND to_date = ? "
                    + "ORDER BY rank";

    private static final String SELECT_LATEST_BEST =
            "SELECT " + SELECT_COLUMNS
                    + "FROM strategy_optimize "
                    + "WHERE ticker = ? AND param_id = ? AND rank = 1 "
                    + "ORDER BY to_date DESC, computed_at DESC "
                    + "LIMIT 1";

    private static final String SELECT_BEST_FOR_TO_DATE =
            "SELECT " + SELECT_COLUMNS
                    + "FROM strategy_optimize "
                    + "WHERE ticker = ? AND param_id = ? AND to_date = ? AND rank = 1 "
                    + "ORDER BY from_date";

    private static final String SELECT_BEST_NEAR_WINDOW =
            "SELECT " + SELECT_COLUMNS
                    + "FROM strategy_optimize "
                    + "WHERE ticker = ? AND param_id = ? AND rank = 1 "
                    + "AND from_date >= ? AND from_date <= ? "
                    + "AND to_date >= ? AND to_date <= ? "
                    + "ORDER BY to_date DESC, computed_at DESC";

    /** Rows saved before the AVOID HIGH/LOW split have null {@code on_avoid_high} and are excluded. */
    private static final String SELECT_EXACT_BEST =
            "SELECT " + SELECT_COLUMNS
                    + "FROM strategy_optimize "
                    + "WHERE ticker = ? AND param_id = ? AND from_date = ? AND to_date = ? AND rank = 1 "
                    + "AND ABS(starting_cash - ?) < 0.005 "
                    + "AND on_avoid_high IS NOT NULL";

    private final Connection connection;

    public StrategyOptimizeRepository(Database database) {
        this.connection = database.connection();
    }

    /**
     * Inserts or replaces one ranked result for
     * {@code (ticker, param_id, from_date, to_date, rank)}.
     *
     * @return 1 on insert or update
     */
    public int upsert(StrategyOptimize row) throws SQLException {
        if (row == null) {
            throw new IllegalArgumentException("row is required");
        }
        int updated = update(row);
        if (updated > 0) {
            return updated;
        }
        return insert(row);
    }

    /**
     * Removes all ranked rows for one ticker window. Used before re-running optimize
     * so a new search replaces the prior saved result.
     */
    public int deleteByWindow(
            String ticker, long paramId, LocalDate fromDate, LocalDate toDate)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(DELETE_BY_WINDOW)) {
            ps.setString(1, normalizeTicker(ticker));
            ps.setLong(2, paramId);
            ps.setDate(3, java.sql.Date.valueOf(fromDate));
            ps.setDate(4, java.sql.Date.valueOf(toDate));
            return ps.executeUpdate();
        }
    }

    public Optional<StrategyOptimize> findByKey(
            String ticker,
            long paramId,
            LocalDate fromDate,
            LocalDate toDate,
            int rank) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BY_KEY)) {
            bindKey(ps, ticker, paramId, fromDate, toDate, rank);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        }
    }

    /**
     * Best ({@code rank = 1}) row searched for exactly this ticker, param, window and starting
     * cash with the current (AVOID HIGH/LOW) strategy grid.
     */
    public Optional<StrategyOptimize> findExactBest(
            String ticker,
            long paramId,
            LocalDate fromDate,
            LocalDate toDate,
            double startingCash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_EXACT_BEST)) {
            ps.setString(1, normalizeTicker(ticker));
            ps.setLong(2, paramId);
            ps.setDate(3, java.sql.Date.valueOf(fromDate));
            ps.setDate(4, java.sql.Date.valueOf(toDate));
            ps.setDouble(5, startingCash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        }
    }

    /** All ranks for one window, ordered by {@code rank}. */
    public List<StrategyOptimize> findByWindow(
            String ticker, long paramId, LocalDate fromDate, LocalDate toDate)
            throws SQLException {
        List<StrategyOptimize> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BY_WINDOW)) {
            ps.setString(1, normalizeTicker(ticker));
            ps.setLong(2, paramId);
            ps.setDate(3, java.sql.Date.valueOf(fromDate));
            ps.setDate(4, java.sql.Date.valueOf(toDate));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
            }
        }
        return rows;
    }

    /** Most recent best ({@code rank = 1}) row for the ticker/param. */
    public Optional<StrategyOptimize> findLatestBest(String ticker, long paramId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_LATEST_BEST)) {
            ps.setString(1, normalizeTicker(ticker));
            ps.setLong(2, paramId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        }
    }

    /**
     * Best ({@code rank = 1}) rows ending on {@code toDate} (e.g. 3-month and 6-month windows).
     */
    public List<StrategyOptimize> findBestForToDate(String ticker, long paramId, LocalDate toDate)
            throws SQLException {
        List<StrategyOptimize> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BEST_FOR_TO_DATE)) {
            ps.setString(1, normalizeTicker(ticker));
            ps.setLong(2, paramId);
            ps.setDate(3, java.sql.Date.valueOf(toDate));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
            }
        }
        return rows;
    }

    /**
     * Best ({@code rank = 1}) row for {@code ticker}/{@code paramId} whose
     * {@code from_date} and {@code to_date} each fall within {@code +/- slackDays}
     * of the requested window. Prefers the closest match when several qualify.
     */
    public Optional<StrategyOptimize> findBestNearWindow(
            String ticker,
            long paramId,
            LocalDate fromDate,
            LocalDate toDate,
            int slackDays) throws SQLException {
        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("fromDate and toDate are required");
        }
        if (slackDays < 0) {
            throw new IllegalArgumentException("slackDays must be >= 0");
        }
        LocalDate fromLo = fromDate.minusDays(slackDays);
        LocalDate fromHi = fromDate.plusDays(slackDays);
        LocalDate toLo = toDate.minusDays(slackDays);
        LocalDate toHi = toDate.plusDays(slackDays);

        List<StrategyOptimize> candidates = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BEST_NEAR_WINDOW)) {
            ps.setString(1, normalizeTicker(ticker));
            ps.setLong(2, paramId);
            ps.setDate(3, java.sql.Date.valueOf(fromLo));
            ps.setDate(4, java.sql.Date.valueOf(fromHi));
            ps.setDate(5, java.sql.Date.valueOf(toLo));
            ps.setDate(6, java.sql.Date.valueOf(toHi));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(mapRow(rs));
                }
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream()
                .min((a, b) -> Long.compare(
                        windowDistanceDays(a, fromDate, toDate),
                        windowDistanceDays(b, fromDate, toDate)));
    }

    private static long windowDistanceDays(
            StrategyOptimize row, LocalDate targetFrom, LocalDate targetTo) {
        long fromDelta = Math.abs(row.fromDate().toEpochDay() - targetFrom.toEpochDay());
        long toDelta = Math.abs(row.toDate().toEpochDay() - targetTo.toEpochDay());
        return fromDelta + toDelta;
    }

    private int insert(StrategyOptimize row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
            int i = 1;
            ps.setString(i++, normalizeTicker(row.ticker()));
            ps.setLong(i++, row.paramId());
            ps.setDate(i++, java.sql.Date.valueOf(row.fromDate()));
            ps.setDate(i++, java.sql.Date.valueOf(row.toDate()));
            ps.setDouble(i++, row.startingCash());
            ps.setInt(i++, row.parts());
            ps.setDouble(i++, row.minBuyConfidence());
            ps.setDouble(i++, row.minSellConfidence());
            ps.setString(i++, row.onBuy());
            ps.setString(i++, row.onSell());
            ps.setString(i++, row.onHold());
            i = bindAvoidIntents(ps, i, row);
            ps.setDouble(i++, row.endingEquity());
            setDouble(ps, i++, row.endingCash());
            setInteger(ps, i++, row.endingShares());
            setDouble(ps, i++, row.lastClose());
            ps.setDouble(i++, row.returnPct());
            setInteger(ps, i++, row.buyCount());
            setInteger(ps, i++, row.sellCount());
            setInteger(ps, i++, row.skippedBuys());
            setDouble(ps, i++, row.buyHoldEquity());
            setDouble(ps, i++, row.buyHoldReturnPct());
            ps.setInt(i, row.rank());
            return ps.executeUpdate();
        }
    }

    private int update(StrategyOptimize row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(UPDATE)) {
            int i = 1;
            ps.setDouble(i++, row.startingCash());
            ps.setInt(i++, row.parts());
            ps.setDouble(i++, row.minBuyConfidence());
            ps.setDouble(i++, row.minSellConfidence());
            ps.setString(i++, row.onBuy());
            ps.setString(i++, row.onSell());
            ps.setString(i++, row.onHold());
            i = bindAvoidIntents(ps, i, row);
            ps.setDouble(i++, row.endingEquity());
            setDouble(ps, i++, row.endingCash());
            setInteger(ps, i++, row.endingShares());
            setDouble(ps, i++, row.lastClose());
            ps.setDouble(i++, row.returnPct());
            setInteger(ps, i++, row.buyCount());
            setInteger(ps, i++, row.sellCount());
            setInteger(ps, i++, row.skippedBuys());
            setDouble(ps, i++, row.buyHoldEquity());
            setDouble(ps, i++, row.buyHoldReturnPct());
            ps.setString(i++, normalizeTicker(row.ticker()));
            ps.setLong(i++, row.paramId());
            ps.setDate(i++, java.sql.Date.valueOf(row.fromDate()));
            ps.setDate(i++, java.sql.Date.valueOf(row.toDate()));
            ps.setInt(i, row.rank());
            return ps.executeUpdate();
        }
    }

    /** Binds {@code on_avoid} (legacy NOT NULL, gets the LOW intent), {@code on_avoid_high}, {@code on_avoid_low}. */
    private static int bindAvoidIntents(PreparedStatement ps, int index, StrategyOptimize row)
            throws SQLException {
        ps.setString(index++, row.onAvoidLow());
        ps.setString(index++, row.onAvoidHigh());
        ps.setString(index++, row.onAvoidLow());
        return index;
    }

    private static void bindKey(
            PreparedStatement ps,
            String ticker,
            long paramId,
            LocalDate fromDate,
            LocalDate toDate,
            int rank) throws SQLException {
        ps.setString(1, normalizeTicker(ticker));
        ps.setLong(2, paramId);
        ps.setDate(3, java.sql.Date.valueOf(fromDate));
        ps.setDate(4, java.sql.Date.valueOf(toDate));
        ps.setInt(5, rank);
    }

    private static StrategyOptimize mapRow(ResultSet rs) throws SQLException {
        return new StrategyOptimize(
                rs.getLong("id"),
                rs.getString("ticker"),
                rs.getLong("param_id"),
                rs.getDate("from_date").toLocalDate(),
                rs.getDate("to_date").toLocalDate(),
                rs.getDouble("starting_cash"),
                rs.getInt("parts"),
                rs.getDouble("min_buy_confidence"),
                rs.getDouble("min_sell_confidence"),
                rs.getString("on_buy"),
                rs.getString("on_sell"),
                rs.getString("on_hold"),
                rs.getString("on_avoid_high"),
                rs.getString("on_avoid_low"),
                rs.getDouble("ending_equity"),
                getDouble(rs, "ending_cash"),
                getInteger(rs, "ending_shares"),
                getDouble(rs, "last_close"),
                rs.getDouble("return_pct"),
                getInteger(rs, "buy_count"),
                getInteger(rs, "sell_count"),
                getInteger(rs, "skipped_buys"),
                getDouble(rs, "buy_hold_equity"),
                getDouble(rs, "buy_hold_return_pct"),
                rs.getInt("rank"),
                toInstant(rs.getTimestamp("computed_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        return ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static void setDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static Double getDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
