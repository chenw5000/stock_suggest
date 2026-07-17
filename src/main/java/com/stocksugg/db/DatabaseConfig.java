package com.stocksugg.db;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Database connection settings. Override via environment variables or system properties:
 * {@code STOCKSUGG_JDBC_URL}, {@code STOCKSUGG_DB_USER}, {@code STOCKSUGG_DB_PASSWORD}.
 */
public final class DatabaseConfig {

    public static final String DEFAULT_JDBC_URL =
            "jdbc:postgresql://localhost:5432/stocksugg?socketTimeout=60000&connectTimeout=15000";
    public static final String DEFAULT_USER = "gcadmin";

    public static final String H2_JDBC_URL =
            "jdbc:h2:file:" + Path.of("data", "stocksugg").toAbsolutePath() + ";AUTO_SERVER=TRUE";
    public static final String H2_USER = "sa";
    public static final String H2_PASSWORD = "";

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public DatabaseConfig(String jdbcUrl, String user, String password) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl is required.");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("user is required.");
        }
        this.jdbcUrl = jdbcUrl.trim();
        this.user = user.trim();
        this.password = password == null ? "" : password;
    }

    public static DatabaseConfig postgres() {
        return load();
    }

    public static DatabaseConfig h2File() {
        return new DatabaseConfig(H2_JDBC_URL, H2_USER, H2_PASSWORD);
    }

    public static DatabaseConfig load() {
        return new DatabaseConfig(
                resolve("STOCKSUGG_JDBC_URL", DEFAULT_JDBC_URL),
                resolve("STOCKSUGG_DB_USER", DEFAULT_USER),
                resolve("STOCKSUGG_DB_PASSWORD", "X3iJNjcc86K"));
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public boolean isPostgres() {
        return jdbcUrl.startsWith("jdbc:postgresql:");
    }

    public boolean isH2() {
        return jdbcUrl.startsWith("jdbc:h2:");
    }

    private static String resolve(String key, String defaultValue) {
        return Optional.ofNullable(System.getenv(key))
                .filter(v -> !v.isBlank())
                .or(() -> Optional.ofNullable(System.getProperty(key)).filter(v -> !v.isBlank()))
                .orElse(defaultValue);
    }
}
