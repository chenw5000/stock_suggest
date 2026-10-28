package com.stocksugg.insider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parsed {@code /api/impact} response. Extra fields stay available on {@link #raw()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InsiderImpactResult(
        String ticker,
        String asOfDate,
        Boolean filingsFound,
        Integer historyWindowDays,
        Integer scoreWindowDays,
        Integer barCountUsed,
        Double medianDollarVolume,
        String trend,
        Integer scoredTradeCount,
        Double flowScore,
        String flowLabel,
        Double meanReturn5d,
        Double meanReturn10d,
        Double meanReturn20d,
        String caveat,
        JsonNode transactions,
        JsonNode raw
) {
    static InsiderImpactResult from(JsonNode root) {
        return new InsiderImpactResult(
                text(root, "ticker"),
                text(root, "asOfDate"),
                bool(root, "filingsFound"),
                integer(root, "historyWindowDays"),
                integer(root, "scoreWindowDays"),
                integer(root, "barCountUsed"),
                number(root, "medianDollarVolume"),
                text(root, "trend"),
                integer(root, "scoredTradeCount"),
                number(root, "flowScore"),
                text(root, "flowLabel"),
                number(root, "meanReturn5d"),
                number(root, "meanReturn10d"),
                number(root, "meanReturn20d"),
                text(root, "caveat"),
                root.get("transactions"),
                root);
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Boolean bool(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asBoolean();
    }

    private static Integer integer(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asInt();
    }

    private static Double number(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asDouble();
    }
}
