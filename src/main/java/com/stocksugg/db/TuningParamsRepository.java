package com.stocksugg.db;

import com.stocksugg.stock.TuningParams;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads {@code tuning_params} experiment rows by id. */
public final class TuningParamsRepository {

    private static final String SELECT_ALL = """
            SELECT id, num_date_point,
                   ma5_weight, ma10_weight, ma20_weight, ma50_weight, ma200_weight,
                   rsi_weight, cmo_weight, cmf_weight,
                   name, description
            FROM tuning_params
            ORDER BY id
            """;

    private static final String SELECT_BY_ID = """
            SELECT id, num_date_point,
                   ma5_weight, ma10_weight, ma20_weight, ma50_weight, ma200_weight,
                   rsi_weight, cmo_weight, cmf_weight,
                   name, description
            FROM tuning_params
            WHERE id = ?
            """;

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

    /** Loads the row or throws if missing. */
    public TuningParams requireById(long id) throws SQLException {
        return findById(id).orElseThrow(() -> new IllegalArgumentException(
                "No tuning_params row with id=" + id));
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
                rs.getString("description"));
    }

    private static Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
