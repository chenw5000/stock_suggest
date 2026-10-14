package com.stocksugg.db;

import com.stocksugg.stock.BacktestStrategy;
import com.stocksugg.stock.PerformanceTickerResult;
import com.stocksugg.stock.PerformanceWeek;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persistence for weekly performance snapshots ({@code performance_week} + tickers). */
public final class PerformanceRepository {

    private static final String WEEK_COLUMNS = """
            id, week_ending, as_of_date, param_id, train_from, train_to, test_from, test_to,
            trigger_source, tickers_total, evaluated, skipped, beat_count, not_beat_count,
            invested, strategy_total, buy_hold_total, duration_ms, computed_at
            """;

    private static final String INSERT_WEEK = """
            INSERT INTO performance_week (
                week_ending, as_of_date, param_id, train_from, train_to, test_from, test_to,
                trigger_source, tickers_total, evaluated, skipped, beat_count, not_beat_count,
                invested, strategy_total, buy_hold_total, duration_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_TICKER = """
            INSERT INTO performance_week_ticker (
                week_id, ticker, status, skip_reason,
                parts, min_buy_confidence, min_sell_confidence,
                on_buy, on_sell, on_avoid_high, on_avoid_low, strategy_cached,
                train_return_pct, train_buy_hold_return_pct, test_trading_days,
                strategy_equity, strategy_return_pct, buy_count, sell_count,
                buy_hold_equity, buy_hold_return_pct, beat
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_WEEK =
            "SELECT " + WEEK_COLUMNS + "FROM performance_week WHERE week_ending = ? AND param_id = ?";

    private static final String SELECT_LATEST_WEEK =
            "SELECT " + WEEK_COLUMNS + "FROM performance_week WHERE param_id = ? "
                    + "ORDER BY week_ending DESC LIMIT 1";

    private static final String SELECT_WEEKS =
            "SELECT " + WEEK_COLUMNS + "FROM performance_week WHERE param_id = ? ORDER BY week_ending";

    private static final String SELECT_TICKERS = """
            SELECT ticker, status, skip_reason,
                   parts, min_buy_confidence, min_sell_confidence,
                   on_buy, on_sell, on_avoid_high, on_avoid_low, strategy_cached,
                   train_return_pct, train_buy_hold_return_pct, test_trading_days,
                   strategy_equity, strategy_return_pct, buy_count, sell_count,
                   buy_hold_equity, buy_hold_return_pct, beat
            FROM performance_week_ticker
            WHERE week_id = ?
            ORDER BY CASE WHEN status = 'OK' THEN 0 ELSE 1 END, ticker
            """;

    private final Connection connection;

    public PerformanceRepository(Database database) {
        this.connection = database.connection();
    }

    /**
     * Replaces the snapshot for {@code (week_ending, param_id)} with {@code week} and its ticker
     * rows in one transaction, and returns the saved week (with id / computed_at).
     */
    public PerformanceWeek save(PerformanceWeek week, List<PerformanceTickerResult> rows)
            throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Optional<PerformanceWeek> existing = findWeek(week.weekEnding(), week.paramId());
            if (existing.isPresent()) {
                deleteWeek(existing.get().id());
            }
            insertWeek(week);
            PerformanceWeek saved = findWeek(week.weekEnding(), week.paramId())
                    .orElseThrow(() -> new SQLException("performance_week insert not found"));
            for (PerformanceTickerResult row : rows) {
                insertTicker(saved.id(), row);
            }
            connection.commit();
            return saved;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    public Optional<PerformanceWeek> findWeek(LocalDate weekEnding, long paramId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_WEEK)) {
            ps.setDate(1, java.sql.Date.valueOf(weekEnding));
            ps.setLong(2, paramId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapWeek(rs)) : Optional.empty();
            }
        }
    }

    public Optional<PerformanceWeek> findLatestWeek(long paramId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_LATEST_WEEK)) {
            ps.setLong(1, paramId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapWeek(rs)) : Optional.empty();
            }
        }
    }

    /** All saved weeks for the param, oldest first. */
    public List<PerformanceWeek> listWeeks(long paramId) throws SQLException {
        List<PerformanceWeek> weeks = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_WEEKS)) {
            ps.setLong(1, paramId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    weeks.add(mapWeek(rs));
                }
            }
        }
        return weeks;
    }

    /** Ticker rows for one week: evaluated tickers first, then skipped, each by ticker. */
    public List<PerformanceTickerResult> findTickers(long weekId) throws SQLException {
        List<PerformanceTickerResult> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_TICKERS)) {
            ps.setLong(1, weekId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapTicker(rs));
                }
            }
        }
        return rows;
    }

    private void deleteWeek(long weekId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM performance_week_ticker WHERE week_id = ?")) {
            ps.setLong(1, weekId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM performance_week WHERE id = ?")) {
            ps.setLong(1, weekId);
            ps.executeUpdate();
        }
    }

    private void insertWeek(PerformanceWeek week) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_WEEK)) {
            int i = 1;
            ps.setDate(i++, java.sql.Date.valueOf(week.weekEnding()));
            ps.setDate(i++, java.sql.Date.valueOf(week.asOfDate()));
            ps.setLong(i++, week.paramId());
            ps.setDate(i++, java.sql.Date.valueOf(week.trainFrom()));
            ps.setDate(i++, java.sql.Date.valueOf(week.trainTo()));
            ps.setDate(i++, java.sql.Date.valueOf(week.testFrom()));
            ps.setDate(i++, java.sql.Date.valueOf(week.testTo()));
            ps.setString(i++, week.triggerSource());
            ps.setInt(i++, week.tickersTotal());
            ps.setInt(i++, week.evaluated());
            ps.setInt(i++, week.skipped());
            ps.setInt(i++, week.beatCount());
            ps.setInt(i++, week.notBeatCount());
            ps.setDouble(i++, week.invested());
            ps.setDouble(i++, week.strategyTotal());
            ps.setDouble(i++, week.buyHoldTotal());
            if (week.durationMs() == null) {
                ps.setNull(i, Types.BIGINT);
            } else {
                ps.setLong(i, week.durationMs());
            }
            ps.executeUpdate();
        }
    }

    private void insertTicker(long weekId, PerformanceTickerResult row) throws SQLException {
        BacktestStrategy strategy = row.strategy();
        try (PreparedStatement ps = connection.prepareStatement(INSERT_TICKER)) {
            int i = 1;
            ps.setLong(i++, weekId);
            ps.setString(i++, row.ticker());
            ps.setString(i++, row.status());
            setString(ps, i++, row.skipReason());
            setInteger(ps, i++, strategy == null ? null : strategy.parts());
            setDouble(ps, i++, strategy == null ? null : strategy.minBuyConfidence());
            setDouble(ps, i++, strategy == null ? null : strategy.minSellConfidence());
            setString(ps, i++, strategy == null ? null : strategy.onBuy().name());
            setString(ps, i++, strategy == null ? null : strategy.onSell().name());
            setString(ps, i++, strategy == null ? null : strategy.onAvoidHigh().name());
            setString(ps, i++, strategy == null ? null : strategy.onAvoidLow().name());
            setBoolean(ps, i++, row.strategyCached());
            setDouble(ps, i++, row.trainReturnPct());
            setDouble(ps, i++, row.trainBuyHoldReturnPct());
            setInteger(ps, i++, row.testTradingDays());
            setDouble(ps, i++, row.strategyEquity());
            setDouble(ps, i++, row.strategyReturnPct());
            setInteger(ps, i++, row.buyCount());
            setInteger(ps, i++, row.sellCount());
            setDouble(ps, i++, row.buyHoldEquity());
            setDouble(ps, i++, row.buyHoldReturnPct());
            setBoolean(ps, i, row.beat());
            ps.executeUpdate();
        }
    }

    private static PerformanceWeek mapWeek(ResultSet rs) throws SQLException {
        long duration = rs.getLong("duration_ms");
        Long durationMs = rs.wasNull() ? null : duration;
        Timestamp computedAt = rs.getTimestamp("computed_at");
        return new PerformanceWeek(
                rs.getLong("id"),
                rs.getDate("week_ending").toLocalDate(),
                rs.getDate("as_of_date").toLocalDate(),
                rs.getLong("param_id"),
                rs.getDate("train_from").toLocalDate(),
                rs.getDate("train_to").toLocalDate(),
                rs.getDate("test_from").toLocalDate(),
                rs.getDate("test_to").toLocalDate(),
                rs.getString("trigger_source"),
                rs.getInt("tickers_total"),
                rs.getInt("evaluated"),
                rs.getInt("skipped"),
                rs.getInt("beat_count"),
                rs.getInt("not_beat_count"),
                rs.getDouble("invested"),
                rs.getDouble("strategy_total"),
                rs.getDouble("buy_hold_total"),
                durationMs,
                computedAt == null ? null : computedAt.toInstant());
    }

    private static PerformanceTickerResult mapTicker(ResultSet rs) throws SQLException {
        String onBuy = rs.getString("on_buy");
        BacktestStrategy strategy = null;
        if (onBuy != null) {
            strategy = new BacktestStrategy(
                    rs.getInt("parts"),
                    rs.getDouble("min_buy_confidence"),
                    rs.getDouble("min_sell_confidence"),
                    BacktestStrategy.TradeIntent.valueOf(onBuy),
                    BacktestStrategy.TradeIntent.valueOf(rs.getString("on_sell")),
                    BacktestStrategy.TradeIntent.NONE,
                    BacktestStrategy.TradeIntent.valueOf(rs.getString("on_avoid_high")),
                    BacktestStrategy.TradeIntent.valueOf(rs.getString("on_avoid_low")));
        }
        return new PerformanceTickerResult(
                rs.getString("ticker"),
                rs.getString("status"),
                rs.getString("skip_reason"),
                strategy,
                getBoolean(rs, "strategy_cached"),
                getDouble(rs, "train_return_pct"),
                getDouble(rs, "train_buy_hold_return_pct"),
                getInteger(rs, "test_trading_days"),
                getDouble(rs, "strategy_equity"),
                getDouble(rs, "strategy_return_pct"),
                getInteger(rs, "buy_count"),
                getInteger(rs, "sell_count"),
                getDouble(rs, "buy_hold_equity"),
                getDouble(rs, "buy_hold_return_pct"),
                getBoolean(rs, "beat"));
    }

    private static void setString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static void setBoolean(PreparedStatement ps, int index, Boolean value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BOOLEAN);
        } else {
            ps.setBoolean(index, value);
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

    private static Boolean getBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }
}
