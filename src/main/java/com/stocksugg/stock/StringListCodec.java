package com.stocksugg.stock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Encodes/decodes bullet lists for {@code thesis}/{@code risks} TEXT columns.
 * New writes use a JSON string array; reads also accept legacy newline-separated text.
 */
public final class StringListCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private StringListCodec() {}

    /** Serializes a list to a JSON array string for DB storage. */
    public static String encode(List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<String> cleaned = items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(ArrayList::new));
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(cleaned);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode string list as JSON", e);
        }
    }

    /**
     * Parses DB text into a list. Accepts JSON arrays ({@code ["a","b"]}) or legacy
     * newline-separated plain text.
     */
    public static List<String> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                List<String> parsed = MAPPER.readValue(trimmed, STRING_LIST);
                if (parsed == null || parsed.isEmpty()) {
                    return List.of();
                }
                return parsed.stream()
                        .filter(item -> item != null && !item.isBlank())
                        .map(String::trim)
                        .toList();
            } catch (Exception ignored) {
                // Fall through to legacy newline parsing.
            }
        }
        List<String> lines = new ArrayList<>();
        for (String line : trimmed.split("\\R")) {
            String item = line.trim();
            if (!item.isEmpty()) {
                lines.add(item);
            }
        }
        return List.copyOf(lines);
    }
}
