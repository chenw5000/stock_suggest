package com.stocksugg.stock;

import com.stocksugg.db.AdminRepository;
import com.stocksugg.db.Database;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the watched ticker list from the {@code admin} table ({@code TICKERS}).
 * Value format: comma-separated symbols, e.g. {@code AAPL,TSLA,MSFT}. Max {@link #MAX_TICKERS}.
 */
public final class TickerList {

    public static final String ADMIN_KEY = "TICKERS";
    public static final int MAX_TICKERS = 20;

    private TickerList() {}

    public static List<String> loadFromAdmin() {
        try (Database db = new Database()) {
            AdminRepository repository = new AdminRepository(db);
            Optional<Map<String, Object>> row = repository.findByKey(ADMIN_KEY);
            if (row.isEmpty()) {
                throw new IllegalStateException(
                        "Missing admin property '" + ADMIN_KEY
                                + "'. Set it via admin.html to a comma-separated ticker list "
                                + "(max " + MAX_TICKERS + ").");
            }
            Object value = row.get().get("value");
            return parse(value == null ? "" : String.valueOf(value));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + ADMIN_KEY + " from admin table: "
                    + e.getMessage(), e);
        }
    }

    public static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "Admin property '" + ADMIN_KEY + "' is empty. Provide tickers separated by ','.");
        }

        Set<String> unique = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String ticker = part.trim().toUpperCase(Locale.ROOT);
            if (ticker.isEmpty()) {
                continue;
            }
            if (!ticker.matches("[A-Z][A-Z0-9.\\-]{0,9}")) {
                throw new IllegalArgumentException(
                        "Invalid ticker '" + ticker + "' in admin property '" + ADMIN_KEY + "'.");
            }
            unique.add(ticker);
            if (unique.size() > MAX_TICKERS) {
                throw new IllegalArgumentException(
                        "Admin property '" + ADMIN_KEY + "' has more than " + MAX_TICKERS
                                + " tickers.");
            }
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException(
                    "Admin property '" + ADMIN_KEY + "' has no valid tickers.");
        }
        return new ArrayList<>(unique);
    }
}
