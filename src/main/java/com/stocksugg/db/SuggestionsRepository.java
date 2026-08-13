package com.stocksugg.db;

import com.stocksugg.stock.SuggestionUpdate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Versioned Gemini labels in {@code suggestions}, keyed by {@code (stock_id, param_id)}.
 */
public final class SuggestionsRepository {

    private static final String UPDATE = """
            UPDATE suggestions
            SET suggestedaction = ?,
                confidence = ?,
                suggestedstopprice = ?,
                suggestedentryprice = ?,
                suggestedprofitprice = ?,
                thesis = ?,
                risks = ?
            WHERE stock_id = ? AND param_id = ?
            """;

    private static final String INSERT = """
            INSERT INTO suggestions (
                stock_id, param_id,
                suggestedaction, confidence,
                suggestedstopprice, suggestedentryprice, suggestedprofitprice,
                thesis, risks
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final Connection connection;

    public SuggestionsRepository(Database database) {
        this.connection = database.connection();
    }

    /**
     * Inserts or replaces the suggestion for one stock row under one tuning params set.
     * @return 1 on insert or update
     */
    public int upsert(long stockId, long paramId, SuggestionUpdate suggestion) throws SQLException {
        if (suggestion == null) {
            throw new IllegalArgumentException("suggestion is required");
        }
        int updated = update(stockId, paramId, suggestion);
        if (updated > 0) {
            return updated;
        }
        return insert(stockId, paramId, suggestion);
    }

    private int update(long stockId, long paramId, SuggestionUpdate suggestion) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(UPDATE)) {
            bindSuggestionFields(ps, 1, suggestion);
            ps.setLong(8, stockId);
            ps.setLong(9, paramId);
            return ps.executeUpdate();
        }
    }

    private int insert(long stockId, long paramId, SuggestionUpdate suggestion) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
            ps.setLong(1, stockId);
            ps.setLong(2, paramId);
            bindSuggestionFields(ps, 3, suggestion);
            return ps.executeUpdate();
        }
    }

    private static void bindSuggestionFields(
            PreparedStatement ps, int startIndex, SuggestionUpdate suggestion) throws SQLException {
        int i = startIndex;
        setString(ps, i++, normalizeAction(suggestion.suggestedAction()));
        setFloat(ps, i++, suggestion.confidence());
        setFloat(ps, i++, suggestion.suggestedStopPrice());
        setFloat(ps, i++, suggestion.suggestedEntryPrice());
        setFloat(ps, i++, suggestion.suggestedProfitPrice());
        setString(ps, i++, suggestion.thesis());
        setString(ps, i, suggestion.risks());
    }

    private static String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        return action.trim().toUpperCase();
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
