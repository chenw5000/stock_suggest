package com.stocksugg.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Database connection settings.
 * <p>
 * Resolution order for each setting (first non-blank wins):
 * <ol>
 *   <li>Environment variable ({@code STOCKSUGG_JDBC_URL}, {@code STOCKSUGG_DB_USER},
 *       {@code STOCKSUGG_DB_PASSWORD})</li>
 *   <li>JVM system property with the same names ({@code -DSTOCKSUGG_DB_USER=...})</li>
 *   <li>External properties file (see below)</li>
 *   <li>Non-secret defaults (JDBC URL / user only — never a password)</li>
 * </ol>
 * Properties file locations (first existing file wins), or set {@code STOCKSUGG_CONFIG}:
 * {@code config/database.properties}, {@code database.properties},
 * {@code ~/.stocksugg/database.properties}.
 */
public final class DatabaseConfig {

    public static final String DEFAULT_JDBC_URL =
            "jdbc:postgresql://localhost:5432/stocksugg?socketTimeout=60000&connectTimeout=15000";
    public static final String DEFAULT_USER = "gcadmin";

    public static final String H2_JDBC_URL =
            "jdbc:h2:file:" + Path.of("data", "stocksugg").toAbsolutePath() + ";AUTO_SERVER=TRUE";
    public static final String H2_USER = "sa";
    public static final String H2_PASSWORD = "";

    private static final String KEY_URL = "STOCKSUGG_JDBC_URL";
    private static final String KEY_USER = "STOCKSUGG_DB_USER";
    private static final String KEY_PASSWORD = "STOCKSUGG_DB_PASSWORD";
    private static final String KEY_CONFIG = "STOCKSUGG_CONFIG";

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
        Properties fileProps = loadFileProperties();
        String jdbcUrl = resolve(KEY_URL, fileProps, DEFAULT_JDBC_URL);
        String user = resolve(KEY_USER, fileProps, DEFAULT_USER);
        String password = resolve(KEY_PASSWORD, fileProps, null);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("""
                    Missing database password.
                    Set STOCKSUGG_DB_PASSWORD as an environment variable / -D system property,
                    or create config/database.properties (see config/database.properties.example).
                    """);
        }
        return new DatabaseConfig(jdbcUrl, user, password);
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

    private static String resolve(String key, Properties fileProps, String defaultValue) {
        return Optional.ofNullable(System.getenv(key))
                .filter(v -> !v.isBlank())
                .or(() -> Optional.ofNullable(System.getProperty(key)).filter(v -> !v.isBlank()))
                .or(() -> Optional.ofNullable(fileProps.getProperty(key)).filter(v -> !v.isBlank()))
                .or(() -> Optional.ofNullable(fileProps.getProperty(toPropertyKey(key)))
                        .filter(v -> !v.isBlank()))
                .orElse(defaultValue);
    }

    /** Accepts both STOCKSUGG_DB_USER and stocksugg.db.user style keys in the file. */
    private static String toPropertyKey(String envKey) {
        return envKey.toLowerCase().replace('_', '.');
    }

    private static Properties loadFileProperties() {
        Properties props = new Properties();
        Path configPath = findConfigFile();
        if (configPath == null) {
            return props;
        }
        try (InputStream in = Files.newInputStream(configPath)) {
            props.load(in);
            System.out.println("Loaded database config from " + configPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Could not read " + configPath.toAbsolutePath() + ": " + e.getMessage());
        }
        return props;
    }

    private static Path findConfigFile() {
        Optional<String> explicit = Optional.ofNullable(System.getenv(KEY_CONFIG))
                .filter(v -> !v.isBlank())
                .or(() -> Optional.ofNullable(System.getProperty(KEY_CONFIG)).filter(v -> !v.isBlank()));
        if (explicit.isPresent()) {
            Path path = Path.of(explicit.get());
            return Files.isRegularFile(path) ? path : null;
        }

        Path[] candidates = {
                Path.of("config", "database.properties"),
                Path.of("database.properties"),
                Path.of(System.getProperty("user.home"), ".stocksugg", "database.properties")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
