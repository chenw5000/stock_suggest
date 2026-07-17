package com.stocksugg.web;

import com.stocksugg.App;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * JSON API and legacy URL redirects for Tomcat. Static HTML lives under {@code webapp/}.
 */
@WebServlet(name = "StockSuggServlet", urlPatterns = {
        "/health",
        "/api/suggest/*",
        "/api/history/*",
        "/api/admin",
        "/api/admin/*",
        "/api/batch",
        "/suggest/*"
})
public final class StockSuggServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getServletPath();

        if ("/health".equals(path)) {
            writeJson(resp, HttpServletResponse.SC_OK, Map.of(
                    "status", "ok",
                    "service", "StockSugg",
                    "container", "tomcat"));
            return;
        }

        if ("/api/suggest".equals(path)) {
            writeSuggestApi(req.getPathInfo(), resp);
            return;
        }

        if ("/api/history".equals(path)) {
            writeHistoryApi(req, resp);
            return;
        }

        if ("/api/admin".equals(path)) {
            writeAdminList(resp);
            return;
        }

        if ("/api/batch".equals(path)) {
            writeBatchStatus(resp);
            return;
        }

        if ("/suggest".equals(path)) {
            redirectToSuggestPage(req, resp);
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if ("/api/admin".equals(req.getServletPath())) {
            writeAdminUpsert(req, resp);
            return;
        }
        resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getServletPath();
        if ("/api/batch".equals(path)) {
            writeBatchStart(resp);
            return;
        }
        if ("/api/admin".equals(path)) {
            writeAdminUpsert(req, resp);
            return;
        }
        resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if ("/api/admin".equals(req.getServletPath())) {
            writeAdminDelete(req, resp);
            return;
        }
        resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    private static void writeAdminList(HttpServletResponse resp) throws IOException {
        try {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json; charset=UTF-8");
            resp.getWriter().write(AdminApi.listJson());
        } catch (Exception e) {
            writeJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of(
                    "error", "Failed to load admin properties: " + e.getMessage()));
        }
    }

    private static void writeAdminUpsert(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String body = req.getReader().lines().collect(Collectors.joining("\n"));
        try {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json; charset=UTF-8");
            resp.getWriter().write(AdminApi.upsertJson(body));
        } catch (IllegalArgumentException e) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            writeJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of(
                    "error", "Failed to save admin property: " + e.getMessage()));
        }
    }

    private static void writeAdminDelete(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String key = extractPathSegment(req.getPathInfo());
        if (key == null) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of(
                    "error", "Missing key. Use DELETE /api/admin/{key}."));
            return;
        }
        try {
            String decoded = URLDecoder.decode(key, StandardCharsets.UTF_8);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json; charset=UTF-8");
            resp.getWriter().write(AdminApi.deleteJson(decoded));
        } catch (IllegalArgumentException e) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            writeJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of(
                    "error", "Failed to delete admin property: " + e.getMessage()));
        }
    }

    private static void writeBatchStart(HttpServletResponse resp) throws IOException {
        try {
            boolean started = App.startBatchJobAsync();
            Map<String, Object> body = Map.of(
                    "started", started,
                    "running", App.isBatchRunning(),
                    "message", started
                            ? "Batch job started (Yahoo refresh + Gemini suggestions)."
                            : "Batch job is already running.");
            writeJson(resp,
                    started ? HttpServletResponse.SC_ACCEPTED : HttpServletResponse.SC_CONFLICT,
                    body);
        } catch (Exception e) {
            writeJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of(
                    "error", "Failed to start batch job: " + e.getMessage()));
        }
    }

    private static void writeBatchStatus(HttpServletResponse resp) throws IOException {
        try {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json; charset=UTF-8");
            resp.getWriter().write(BatchApi.statusJson());
        } catch (Exception e) {
            writeJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of(
                    "error", "Failed to read batch status: " + e.getMessage()));
        }
    }

    private static void writeSuggestApi(String pathInfo, HttpServletResponse resp) throws IOException {
        String rawDate = extractPathSegment(pathInfo);
        if (rawDate == null) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of(
                    "error", "Missing date. Use /api/suggest/yyyy-MM-dd."));
            return;
        }

        final LocalDate date;
        try {
            date = LocalDate.parse(rawDate);
        } catch (DateTimeParseException ex) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of(
                    "error", "Invalid date '" + rawDate + "'. Use yyyy-MM-dd."));
            return;
        }

        try {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json; charset=UTF-8");
            resp.getWriter().write(SuggestApi.suggestionsJson(date));
        } catch (Exception e) {
            writeJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of(
                    "error", "Failed to load suggestions: " + e.getMessage()));
        }
    }

    private static void writeHistoryApi(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String ticker = extractPathSegment(req.getPathInfo());
        if (ticker == null) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of(
                    "error", "Missing ticker. Use /api/history/{TICKER}?page=1"));
            return;
        }

        int page = parsePositiveInt(req.getParameter("page"), 1);
        int pageSize = parsePositiveInt(req.getParameter("pageSize"), HistoryApi.DEFAULT_PAGE_SIZE);

        try {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json; charset=UTF-8");
            resp.getWriter().write(HistoryApi.historyJson(ticker, page, pageSize));
        } catch (IllegalArgumentException e) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            writeJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of(
                    "error", "Failed to load history: " + e.getMessage()));
        }
    }

    private static void redirectToSuggestPage(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String rawDate = extractPathSegment(req.getPathInfo());
        if (rawDate == null) {
            resp.sendRedirect(req.getContextPath() + "/suggest.html");
            return;
        }
        try {
            LocalDate.parse(rawDate);
        } catch (DateTimeParseException ex) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of(
                    "error", "Invalid date '" + rawDate + "'. Use yyyy-MM-dd."));
            return;
        }
        resp.sendRedirect(req.getContextPath() + "/suggest.html?date=" + rawDate);
    }

    private static String extractPathSegment(String pathInfo) {
        if (pathInfo == null || pathInfo.isBlank() || "/".equals(pathInfo)) {
            return null;
        }
        String value = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
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

    private static void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(SuggestApi.mapper().writeValueAsString(body));
    }
}
