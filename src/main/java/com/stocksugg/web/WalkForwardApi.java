package com.stocksugg.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocksugg.db.Database;
import com.stocksugg.db.WalkForwardRepository;
import com.stocksugg.stock.GeminiStockAdvisor;
import com.stocksugg.stock.WalkForward;
import com.stocksugg.stock.WalkForwardRunner;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Walk-forward runs for {@code walkforward.html} ({@code /api/walkforward/*}). */
public final class WalkForwardApi {

    /** HTTP status plus JSON body for the endpoint that starts the background job. */
    public record Response(int status, String json) {}

    private WalkForwardApi() {}

    /** Saved runs (newest first) plus the default test span for the run form. */
    public static String runsJson(String paramRaw) throws Exception {
        long paramId = parseParamId(paramRaw);
        LocalDate[] span = WalkForwardRunner.defaultSpan(paramId);
        try (Database db = new Database()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("paramId", paramId);
            body.put("defaultFrom", span[0]);
            body.put("defaultTo", span[1]);
            body.put("runs", new WalkForwardRepository(db).list(paramId));
            return SuggestApi.mapper().writeValueAsString(body);
        }
    }

    /** One saved run with its full report; the newest run when {@code idRaw} is blank. */
    public static String runJson(String idRaw, String paramRaw) throws Exception {
        long paramId = parseParamId(paramRaw);
        try (Database db = new Database()) {
            WalkForwardRepository repo = new WalkForwardRepository(db);
            Optional<WalkForwardRepository.StoredRun> run;
            if (idRaw == null || idRaw.isBlank()) {
                run = repo.findLatest(paramId);
            } else {
                try {
                    run = repo.find(Long.parseLong(idRaw.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("id must be an integer");
                }
            }
            if (run.isEmpty()) {
                return "{\"found\":false}";
            }
            return "{\"found\":true,\"summary\":"
                    + SuggestApi.mapper().writeValueAsString(run.get().summary())
                    + ",\"report\":" + run.get().resultJson() + "}";
        }
    }

    public static String statusJson() throws Exception {
        return SuggestApi.mapper().writeValueAsString(WalkForwardRunner.status());
    }

    /** {@code {cadence?, from?, to?, recompute?, param?}}: starts a run in the background. */
    public static Response startJson(String requestBody) throws Exception {
        JsonNode root = SuggestApi.mapper().readTree(requestBody == null ? "{}" : requestBody);
        String cadenceRaw = text(root, "cadence");
        WalkForward.Cadence cadence;
        try {
            cadence = cadenceRaw == null || cadenceRaw.isBlank()
                    ? WalkForward.Cadence.QUARTERLY
                    : WalkForward.Cadence.valueOf(cadenceRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cadence must be QUARTERLY or WEEKLY");
        }
        LocalDate from = optionalDate(text(root, "from"), "from");
        LocalDate to = optionalDate(text(root, "to"), "to");
        long paramId = parseParamId(text(root, "param"));
        boolean recompute = root.path("recompute").asBoolean(false);

        WalkForwardRunner.StartResult result = WalkForwardRunner.start(cadence, from, to, paramId, recompute);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("started", result == WalkForwardRunner.StartResult.STARTED);
        body.put("message", switch (result) {
            case STARTED -> "Started.";
            case ALREADY_RUNNING -> "A walk-forward run is already in progress.";
            case BATCH_RUNNING -> "The daily batch is running; try again when it finishes.";
        });
        body.put("status", WalkForwardRunner.status());
        int http = result == WalkForwardRunner.StartResult.STARTED ? 202 : 409;
        return new Response(http, SuggestApi.mapper().writeValueAsString(body));
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static LocalDate optionalDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be yyyy-MM-dd");
        }
    }

    private static long parseParamId(String raw) {
        if (raw == null || raw.isBlank()) {
            return GeminiStockAdvisor.DEFAULT_PARAM_ID;
        }
        try {
            long paramId = Long.parseLong(raw.trim());
            if (paramId < 1) {
                throw new IllegalArgumentException("param must be >= 1");
            }
            return paramId;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("param must be an integer");
        }
    }
}
