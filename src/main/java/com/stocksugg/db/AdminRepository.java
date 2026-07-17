package com.stocksugg.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** CRUD helpers for the {@code admin} key/value table. */
public final class AdminRepository {

    private static final String SELECT_ALL = """
            SELECT id, "key", "value"
            FROM admin
            ORDER BY id, "key"
            """;

    private static final String SELECT_BY_KEY = """
            SELECT id, "key", "value"
            FROM admin
            WHERE "key" = ?
            """;

    private static final String INSERT = """
            INSERT INTO admin (id, "key", "value")
            VALUES (?, ?, ?)
            """;

    private static final String UPDATE_BY_KEY = """
            UPDATE admin
            SET "value" = ?
            WHERE "key" = ?
            """;

    private static final String DELETE_BY_KEY = """
            DELETE FROM admin
            WHERE "key" = ?
            """;

    private static final String NEXT_ID = """
            SELECT COALESCE(MAX(id), 0) + 1 FROM admin
            """;

    private final Connection connection;

    public AdminRepository(Database database) {
        this.connection = database.connection();
    }

    public List<Map<String, Object>> findAll() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(SELECT_ALL)) {
            while (rs.next()) {
                rows.add(mapRow(rs));
            }
        }
        return rows;
    }

    public Optional<Map<String, Object>> findByKey(String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BY_KEY)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Creates or updates a property by key. Returns the saved row.
     */
    public Map<String, Object> upsert(String key, String value) throws SQLException {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        if (key.length() > 100) {
            throw new IllegalArgumentException("key must be at most 100 characters");
        }
        String normalizedKey = key.trim();
        String normalizedValue = value == null ? "" : value;
        if (normalizedValue.length() > 155) {
            throw new IllegalArgumentException("value must be at most 155 characters");
        }

        Optional<Map<String, Object>> existing = findByKey(normalizedKey);
        if (existing.isPresent()) {
            try (PreparedStatement ps = connection.prepareStatement(UPDATE_BY_KEY)) {
                ps.setString(1, normalizedValue);
                ps.setString(2, normalizedKey);
                ps.executeUpdate();
            }
        } else {
            int id = nextId();
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                ps.setInt(1, id);
                ps.setString(2, normalizedKey);
                ps.setString(3, normalizedValue);
                ps.executeUpdate();
            }
        }
        return findByKey(normalizedKey).orElseThrow();
    }

    /**
     * Deletes a property by key. Returns {@code true} if a row was removed.
     */
    public boolean deleteByKey(String key) throws SQLException {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        try (PreparedStatement ps = connection.prepareStatement(DELETE_BY_KEY)) {
            ps.setString(1, key.trim());
            return ps.executeUpdate() > 0;
        }
    }

    private int nextId() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(NEXT_ID)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        int id = rs.getInt("id");
        row.put("id", rs.wasNull() ? null : id);
        row.put("key", rs.getString("key"));
        row.put("value", rs.getString("value"));
        return row;
    }
}
