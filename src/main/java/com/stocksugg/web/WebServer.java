package com.stocksugg.web;

import com.stocksugg.App;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Embedded HTTP server for local {@code --embedded} mode. Serves the same static pages + JSON API
 * as the Tomcat WAR.
 */
public final class WebServer {

    public static final int DEFAULT_PORT = 7070;

    private WebServer() {}

    public static Javalin start() {
        return start(DEFAULT_PORT);
    }

    public static Javalin start(int port) {
        Javalin app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "src/main/webapp";
                staticFiles.location = Location.EXTERNAL;
            });

            config.routes.get("/health", ctx -> ctx.json(Map.of(
                    "status", "ok",
                    "service", "StockSugg",
                    "port", port)));

            config.routes.get("/api/suggest/{date}", ctx -> {
                String rawDate = ctx.pathParam("date");
                final LocalDate date;
                try {
                    date = LocalDate.parse(rawDate);
                } catch (DateTimeParseException ex) {
                    ctx.status(HttpStatus.BAD_REQUEST)
                            .json(Map.of("error", "Invalid date '" + rawDate + "'. Use yyyy-MM-dd."));
                    return;
                }
                try {
                    ctx.contentType("application/json")
                            .result(SuggestApi.suggestionsJson(date));
                } catch (Exception e) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .json(Map.of("error", "Failed to load suggestions: " + e.getMessage()));
                }
            });

            config.routes.get("/api/history/{ticker}", ctx -> {
                String ticker = ctx.pathParam("ticker");
                int page = parsePositiveInt(ctx.queryParam("page"), 1);
                int pageSize = parsePositiveInt(ctx.queryParam("pageSize"), HistoryApi.DEFAULT_PAGE_SIZE);
                try {
                    ctx.contentType("application/json")
                            .result(HistoryApi.historyJson(ticker, page, pageSize));
                } catch (IllegalArgumentException e) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
                } catch (Exception e) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .json(Map.of("error", "Failed to load history: " + e.getMessage()));
                }
            });

            config.routes.get("/api/admin", ctx -> {
                try {
                    ctx.contentType("application/json").result(AdminApi.listJson());
                } catch (Exception e) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .json(Map.of("error", "Failed to load admin properties: " + e.getMessage()));
                }
            });

            config.routes.put("/api/admin", ctx -> {
                try {
                    ctx.contentType("application/json").result(AdminApi.upsertJson(ctx.body()));
                } catch (IllegalArgumentException e) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
                } catch (Exception e) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .json(Map.of("error", "Failed to save admin property: " + e.getMessage()));
                }
            });

            config.routes.post("/api/admin", ctx -> {
                try {
                    ctx.contentType("application/json").result(AdminApi.upsertJson(ctx.body()));
                } catch (IllegalArgumentException e) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
                } catch (Exception e) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .json(Map.of("error", "Failed to save admin property: " + e.getMessage()));
                }
            });

            config.routes.delete("/api/admin/{key}", ctx -> {
                try {
                    ctx.contentType("application/json").result(AdminApi.deleteJson(ctx.pathParam("key")));
                } catch (IllegalArgumentException e) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
                } catch (Exception e) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .json(Map.of("error", "Failed to delete admin property: " + e.getMessage()));
                }
            });

            config.routes.post("/api/batch", ctx -> {
                try {
                    boolean started = App.startBatchJobAsync();
                    ctx.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT)
                            .json(Map.of(
                                    "started", started,
                                    "running", App.isBatchRunning(),
                                    "message", started
                                            ? "Batch job started (Yahoo refresh + Gemini suggestions)."
                                            : "Batch job is already running."));
                } catch (Exception e) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .json(Map.of("error", "Failed to start batch job: " + e.getMessage()));
                }
            });

            config.routes.get("/api/batch", ctx -> {
                try {
                    ctx.contentType("application/json").result(BatchApi.statusJson());
                } catch (Exception e) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .json(Map.of("error", "Failed to read batch status: " + e.getMessage()));
                }
            });

            config.routes.get("/suggest/{date}", ctx -> {
                String rawDate = ctx.pathParam("date");
                try {
                    LocalDate.parse(rawDate);
                } catch (DateTimeParseException ex) {
                    ctx.status(HttpStatus.BAD_REQUEST)
                            .json(Map.of("error", "Invalid date '" + rawDate + "'. Use yyyy-MM-dd."));
                    return;
                }
                ctx.redirect("/suggest.html?date=" + rawDate);
            });
        }).start(port);

        System.out.println("Javalin listening at http://localhost:" + port + "/");
        System.out.println("Suggestions UI: http://localhost:" + port + "/suggest.html?date=yyyy-MM-dd");
        System.out.println("Suggestions API: http://localhost:" + port + "/api/suggest/yyyy-MM-dd");
        System.out.println("History UI: http://localhost:" + port + "/history.html?ticker=AAPL");
        System.out.println("History API: http://localhost:" + port + "/api/history/AAPL?page=1");
        System.out.println("Admin UI: http://localhost:" + port + "/admin.html");
        System.out.println("Admin API: http://localhost:" + port + "/api/admin");
        System.out.println("Health check: http://localhost:" + port + "/health");
        return app;
    }

    private static int parsePositiveInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value < 1 ? defaultValue : value;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
