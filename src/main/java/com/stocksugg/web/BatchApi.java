package com.stocksugg.web;

import com.stocksugg.App;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON helpers for starting the Yahoo + Gemini batch job from the admin UI. */
public final class BatchApi {

    private BatchApi() {}

    public static String startJson() throws Exception {
        boolean started = App.startBatchJobAsync();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("started", started);
        body.put("running", App.isBatchRunning());
        body.put("message", started
                ? "Batch job started (Yahoo refresh + Gemini suggestions)."
                : "Batch job is already running.");
        return SuggestApi.mapper().writeValueAsString(body);
    }

    public static String statusJson() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", App.isBatchRunning());
        return SuggestApi.mapper().writeValueAsString(body);
    }
}
