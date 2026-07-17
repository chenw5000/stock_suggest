package com.stocksugg.gemini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeminiConfigTest {

    @Test
    void rejectsBlankModel() {
        assertThrows(IllegalArgumentException.class, () -> new GeminiConfig(" ", "test-key"));
    }

    @Test
    void usesDefaultModel() {
        GeminiConfig config = new GeminiConfig(GeminiConfig.DEFAULT_MODEL, "test-key");
        assertEquals(GeminiConfig.DEFAULT_MODEL, config.model());
        assertEquals("test-key", config.apiKey());
    }

    @Test
    void storesCustomModel() {
        GeminiConfig config = new GeminiConfig("gemini-2.5-pro", "test-key");
        assertEquals("gemini-2.5-pro", config.model());
    }

    @Test
    void rejectsBlankApiKey() {
        assertThrows(IllegalArgumentException.class, () -> new GeminiConfig("gemini-2.5-pro", " "));
    }
}
