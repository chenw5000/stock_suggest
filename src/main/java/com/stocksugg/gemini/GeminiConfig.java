package com.stocksugg.gemini;

import com.stocksugg.db.AdminRepository;
import com.stocksugg.db.Database;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves Gemini credentials and model settings.
 * API key is loaded from the {@code admin} table ({@code GEMINI_API_KEY}),
 * then falls back to {@code GEMINI_API_KEY} / {@code GOOGLE_API_KEY} environment variables.
 */
public final class GeminiConfig {

    public static final String DEFAULT_MODEL = "gemini-3.5-flash";
    public static final String ADMIN_API_KEY = "GEMINI_API_KEY";

    private final String apiKey;
    private final String model;

    public GeminiConfig() {
        this(DEFAULT_MODEL);
    }

    public GeminiConfig(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Gemini model name is required.");
        }
        this.model = model.trim();
        this.apiKey = resolveApiKey();
    }

    /** Explicit credentials (used by tests). */
    public GeminiConfig(String model, String apiKey) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Gemini model name is required.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Gemini API key is required.");
        }
        this.model = model.trim();
        this.apiKey = apiKey.trim();
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    private static String resolveApiKey() {
        Optional<String> fromAdmin = readAdminApiKey();
        if (fromAdmin.isPresent()) {
            return fromAdmin.get();
        }

        Optional<String> fromEnv = firstNonBlank(
                System.getenv("GEMINI_API_KEY"),
                System.getenv("GOOGLE_API_KEY"),
                System.getProperty("GEMINI_API_KEY"),
                System.getProperty("GOOGLE_API_KEY"));
        if (fromEnv.isPresent()) {
            return fromEnv.get();
        }

        throw new IllegalStateException(
                "Missing Gemini API key. Set admin property '" + ADMIN_API_KEY
                        + "' (via admin.html) or environment variable GEMINI_API_KEY / GOOGLE_API_KEY.");
    }

    private static Optional<String> readAdminApiKey() {
        try (Database db = new Database()) {
            AdminRepository repository = new AdminRepository(db);
            Optional<Map<String, Object>> row = repository.findByKey(ADMIN_API_KEY);
            if (row.isEmpty()) {
                return Optional.empty();
            }
            Object value = row.get().get("value");
            if (value == null) {
                return Optional.empty();
            }
            String key = String.valueOf(value).trim();
            return key.isEmpty() ? Optional.empty() : Optional.of(key);
        } catch (Exception e) {
            System.err.println("Could not read " + ADMIN_API_KEY + " from admin table: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<String> firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }
}
