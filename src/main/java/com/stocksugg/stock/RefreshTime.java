package com.stocksugg.stock;

import com.stocksugg.db.AdminRepository;
import com.stocksugg.db.Database;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the daily refresh clock from the {@code admin} table ({@code REFRESH_TIME}).
 * Value format: 24-hour {@code HH:mm} in Pacific Time, e.g. {@code 16:05}.
 */
public final class RefreshTime {

    public static final String ADMIN_KEY = "REFRESH_TIME";
    public static final LocalTime DEFAULT = LocalTime.of(16, 0);
    public static final String DEFAULT_VALUE = "16:00";

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("H:mm");

    private RefreshTime() {}

    /** Loads {@link #ADMIN_KEY} from admin, or {@link #DEFAULT} when missing/invalid. */
    public static LocalTime loadOrDefault() {
        try (Database db = new Database()) {
            return loadOrDefault(new AdminRepository(db));
        } catch (Exception e) {
            System.err.println("Failed to load " + ADMIN_KEY + " from admin; using default "
                    + DEFAULT_VALUE + ": " + e.getMessage());
            return DEFAULT;
        }
    }

    static LocalTime loadOrDefault(AdminRepository repository) throws Exception {
        Optional<Map<String, Object>> row = repository.findByKey(ADMIN_KEY);
        if (row.isEmpty()) {
            return DEFAULT;
        }
        Object value = row.get().get("value");
        try {
            return parse(value == null ? "" : String.valueOf(value));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage() + " Using default " + DEFAULT_VALUE + ".");
            return DEFAULT;
        }
    }

    /**
     * Ensures {@link #ADMIN_KEY} exists in admin so it appears on the admin page.
     * Creates it with {@link #DEFAULT_VALUE} when missing.
     */
    public static LocalTime ensurePresent() {
        try (Database db = new Database()) {
            AdminRepository repository = new AdminRepository(db);
            Optional<Map<String, Object>> row = repository.findByKey(ADMIN_KEY);
            if (row.isEmpty()) {
                repository.upsert(ADMIN_KEY, DEFAULT_VALUE);
                System.out.println("Created admin." + ADMIN_KEY + "=" + DEFAULT_VALUE);
                return DEFAULT;
            }
            Object value = row.get().get("value");
            try {
                return parse(value == null ? "" : String.valueOf(value));
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage() + " Using default " + DEFAULT_VALUE + ".");
                return DEFAULT;
            }
        } catch (Exception e) {
            System.err.println("Failed to ensure " + ADMIN_KEY + "; using default "
                    + DEFAULT_VALUE + ": " + e.getMessage());
            return DEFAULT;
        }
    }

    /** Parses {@code HH:mm} (or {@code H:mm}); blank/null falls back to {@link #DEFAULT}. */
    public static LocalTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String trimmed = raw.trim();
        try {
            return LocalTime.parse(trimmed, HH_MM).withSecond(0).withNano(0);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Admin property '" + ADMIN_KEY + "' must be HH:mm (Pacific), got '"
                            + trimmed + "'.");
        }
    }

    public static String format(LocalTime time) {
        LocalTime value = time == null ? DEFAULT : time.withSecond(0).withNano(0);
        return String.format(Locale.ROOT, "%02d:%02d", value.getHour(), value.getMinute());
    }
}
