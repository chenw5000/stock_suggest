package com.stocksugg.db;

import com.stocksugg.stock.TuningParams;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads and creates {@code tuning_params} experiment rows. */
public final class TuningParamsRepository {

    private static final String COLUMNS = """
            id, num_date_point,
                   ma5_weight, ma10_weight, ma20_weight, ma50_weight, ma200_weight,
                   rsi_weight, cmo_weight, cmf_weight,
                   name, description, prompt_version, temperature, seed
            """;

    private static final String SELECT_ALL = "SELECT " + COLUMNS + " FROM tuning_params ORDER BY id";

    private static final String SELECT_BY_ID = "SELECT " + COLUMNS + " FROM tuning_params WHERE id = ?";

    private static final String SELECT_BY_NAME =
            "SELECT " + COLUMNS + " FROM tuning_params WHERE name = ? ORDER BY id";

    private final Connection connection;

    public TuningParamsRepository(Database database) {
        this.connection = database.connection();
    }

    public List<TuningParams> findAll() throws SQLException {
        List<TuningParams> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(SELECT_ALL)) {
            while (rs.next()) {
                rows.add(mapRow(rs));
            }
        }
        return rows;
    }

    public Optional<TuningParams> findById(long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        }
    }

    /** First row with this exact name. */
    public Optional<TuningParams> findByName(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BY_NAME)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Loads the row or throws if missing. */
    public TuningParams requireById(long id) throws SQLException {
        return findById(id).orElseThrow(() -> new IllegalArgumentException(
                "No tuning_params row with id=" + id));
    }

    /**
     * Inserts {@code row} with id {@code MAX(id) + 1} (its own id is ignored) and returns the saved
     * row. The id is explicit because existing rows were created with explicit ids, so the identity
     * sequence may lag behind them.
     */
    public TuningParams insert(TuningParams row) throws SQLException {
        long id;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(id), 0) + 1 FROM tuning_params")) {
            rs.next();
            id = rs.getLong(1);
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO tuning_params (
                    id, num_date_point, ma5_weight, ma10_weight, ma20_weight, ma50_weight, ma200_weight,
                    rsi_weight, cmo_weight, cmf_weight, name, description, prompt_version, temperature, seed
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, id);
            setInteger(ps, 2, row.numDatePoint());
            setInteger(ps, 3, row.ma5Weight());
            setInteger(ps, 4, row.ma10Weight());
            setInteger(ps, 5, row.ma20Weight());
            setInteger(ps, 6, row.ma50Weight());
            setInteger(ps, 7, row.ma200Weight());
            setInteger(ps, 8, row.rsiWeight());
            setInteger(ps, 9, row.cmoWeight());
            setInteger(ps, 10, row.cmfWeight());
            ps.setString(11, row.name());
            ps.setString(12, row.description());
            ps.setString(13, row.promptVersionOrDefault());
            if (row.temperature() == null) {
                ps.setNull(14, Types.DOUBLE);
            } else {
                ps.setDouble(14, row.temperature());
            }
            setInteger(ps, 15, row.seed());
            ps.executeUpdate();
        }
        return requireById(id);
    }

    private static TuningParams mapRow(ResultSet rs) throws SQLException {
        return new TuningParams(
                rs.getLong("id"),
                getInteger(rs, "num_date_point"),
                getInteger(rs, "ma5_weight"),
                getInteger(rs, "ma10_weight"),
                getInteger(rs, "ma20_weight"),
                getInteger(rs, "ma50_weight"),
                getInteger(rs, "ma200_weight"),
                getInteger(rs, "rsi_weight"),
                getInteger(rs, "cmo_weight"),
                getInteger(rs, "cmf_weight"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("prompt_version"),
                getDouble(rs, "temperature"),
                getInteger(rs, "seed"));
    }

    private static Double getDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
