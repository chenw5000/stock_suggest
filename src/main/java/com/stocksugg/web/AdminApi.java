package com.stocksugg.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stocksugg.db.AdminRepository;
import com.stocksugg.db.Database;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON helpers for admin property API responses. */
public final class AdminApi {

    private AdminApi() {}

    public static String listJson() throws Exception {
        try (Database db = new Database()) {
            AdminRepository repository = new AdminRepository(db);
            List<Map<String, Object>> properties = repository.findAll();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("count", properties.size());
            body.put("properties", properties);
            return SuggestApi.mapper().writeValueAsString(body);
        }
    }

    public static String upsertJson(String requestBody) throws Exception {
        Map<String, Object> payload = SuggestApi.mapper().readValue(
                requestBody == null || requestBody.isBlank() ? "{}" : requestBody,
                new TypeReference<>() {});
        Object keyObj = payload.get("key");
        Object valueObj = payload.get("value");
        String key = keyObj == null ? null : String.valueOf(keyObj);
        String value = valueObj == null ? null : String.valueOf(valueObj);

        try (Database db = new Database()) {
            AdminRepository repository = new AdminRepository(db);
            Map<String, Object> saved = repository.upsert(key, value);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("property", saved);
            return SuggestApi.mapper().writeValueAsString(body);
        }
    }

    public static String deleteJson(String key) throws Exception {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        try (Database db = new Database()) {
            AdminRepository repository = new AdminRepository(db);
            boolean deleted = repository.deleteByKey(key);
            if (!deleted) {
                throw new IllegalArgumentException("No admin property with key '" + key.trim() + "'");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("deleted", true);
            body.put("key", key.trim());
            return SuggestApi.mapper().writeValueAsString(body);
        }
    }
}
