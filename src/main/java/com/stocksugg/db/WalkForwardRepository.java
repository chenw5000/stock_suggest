package com.stocksugg.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persistence for walk-forward runs ({@code walk_forward_run}); the report itself is stored as JSON. */
public final class WalkForwardRepository {

    /** Listing row (no report body). */
    public record RunSummary(
            long id,
            String cadence,
            LocalDate testFrom,
            LocalDate testTo,
            long paramId,
            int tickersTotal,
            int included,
            Double walkForwardReturnPct,
            Double buyHoldReturnPct,
            Double fixedReturnPct,
            Long durationMs,
            Instant computedAt) {}

    public record StoredRun(RunSummary summary, String resultJson) {}

    private static final String SUMMARY_COLUMNS = """
            id, cadence, test_from, test_to, param_id, tickers_total, included,
            walk_forward_return_pct, buy_hold_return_pct, fixed_return_pct, duration_ms, computed_at
            """;

    private final Connection connection;

    public WalkForwardRepository(Database database) {
        this.connection = database.connection();
    }

    /** Replaces the run for {@code (cadence, test span, param)} and returns its id. */
    public long save(RunSummary summary, String resultJson) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement ps = connection.prepareStatement("""
                    DELETE FROM walk_forward_run
                    WHERE cadence = ? AND test_from = ? AND test_to = ? AND param_id = ?
                    """)) {
                bindKey(ps, summary);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO walk_forward_run (
                        cadence, test_from, test_to, param_id, tickers_total, included,
                        walk_forward_return_pct, buy_hold_return_pct, fixed_return_pct,
                        duration_ms, result_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                bindKey(ps, summary);
                ps.setInt(5, summary.tickersTotal());
                ps.setInt(6, summary.included());
                ps.setObject(7, summary.walkForwardReturnPct());
                ps.setObject(8, summary.buyHoldReturnPct());
                ps.setObject(9, summary.fixedReturnPct());
                ps.setObject(10, summary.durationMs());
                ps.setString(11, resultJson);
                ps.executeUpdate();
            }
            long id;
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id FROM walk_forward_run
                    WHERE cadence = ? AND test_from = ? AND test_to = ? AND param_id = ?
                    """)) {
                bindKey(ps, summary);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("walk_forward_run insert not found");
                    }
                    id = rs.getLong(1);
                }
            }
            connection.commit();
            return id;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /** Runs for {@code paramId}, newest first. */
    public List<RunSummary> list(long paramId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT " + SUMMARY_COLUMNS + "FROM walk_forward_run WHERE param_id = ? "
                        + "ORDER BY computed_at DESC, id DESC")) {
            ps.setLong(1, paramId);
            try (ResultSet rs = ps.executeQuery()) {
                List<RunSummary> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapSummary(rs));
                }
                return out;
            }
        }
    }

    public Optional<StoredRun> find(long id) throws SQLException {
        return findOne("SELECT " + SUMMARY_COLUMNS + ", result_json FROM walk_forward_run WHERE id = ?", id);
    }

    public Optional<StoredRun> findLatest(long paramId) throws SQLException {
        return findOne("SELECT " + SUMMARY_COLUMNS + ", result_json FROM walk_forward_run WHERE param_id = ? "
                + "ORDER BY computed_at DESC, id DESC LIMIT 1", paramId);
    }

    private Optional<StoredRun> findOne(String sql, long key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredRun(mapSummary(rs), rs.getString("result_json")));
            }
        }
    }

    private static void bindKey(PreparedStatement ps, RunSummary summary) throws SQLException {
        ps.setString(1, summary.cadence());
        ps.setDate(2, java.sql.Date.valueOf(summary.testFrom()));
        ps.setDate(3, java.sql.Date.valueOf(summary.testTo()));
        ps.setLong(4, summary.paramId());
    }

    private static RunSummary mapSummary(ResultSet rs) throws SQLException {
        long duration = rs.getLong("duration_ms");
        Long durationMs = rs.wasNull() ? null : duration;
        Timestamp computedAt = rs.getTimestamp("computed_at");
        return new RunSummary(
                rs.getLong("id"),
                rs.getString("cadence"),
                rs.getDate("test_from").toLocalDate(),
                rs.getDate("test_to").toLocalDate(),
                rs.getLong("param_id"),
                rs.getInt("tickers_total"),
                rs.getInt("included"),
                nullableDouble(rs, "walk_forward_return_pct"),
                nullableDouble(rs, "buy_hold_return_pct"),
                nullableDouble(rs, "fixed_return_pct"),
                durationMs,
                computedAt == null ? null : computedAt.toInstant());
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
