package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiPromptsTest {

    /** Param 1's suggestions were generated with this exact text; it must never change. */
    @Test
    void v1IsFrozen() throws Exception {
        GeminiPrompts.Prompt v1 = GeminiPrompts.forVersion("v1");
        assertEquals("595c83013b32d6a9916275066f901464ddfc615b51416a94cb468ddd095761b5",
                sha256(v1.systemInstruction()));
        assertEquals("3974025fff25a25248d24f753dfa70accfea324da2b88a7ce3c601b1ed0e7b82",
                sha256(v1.userPrompt(2, "2026-07-01, 2026-07-02", "{}")));
    }

    @Test
    void blankVersionMeansV1AndUnknownVersionFails() {
        assertEquals("v1", GeminiPrompts.forVersion(null).version());
        assertEquals("v1", GeminiPrompts.forVersion(" ").version());
        assertEquals("v3", GeminiPrompts.forVersion("V3").version());
        assertThrows(IllegalArgumentException.class, () -> GeminiPrompts.forVersion("v99"));
        assertEquals("v1", new TuningParams(1, 40, 1, 1, 1, 1, 1, 1, 1, 1, "x", "y").promptVersionOrDefault());
    }

    @Test
    void everyVersionDefinesTheFiveLabelsAndTheResponseContract() {
        assertEquals(5, GeminiPrompts.all().size());
        for (GeminiPrompts.Prompt prompt : GeminiPrompts.all()) {
            String user = prompt.userPrompt(1, "2026-07-01", "{\"ticker\":\"QQQ\"}");
            for (String label : new String[] {"BUY", "HOLD", "SELL", "AVOID", "NA"}) {
                assertTrue(prompt.systemInstruction().contains("- " + label + ":"), prompt.version() + " " + label);
                assertTrue(user.contains("- " + label + ":"), prompt.version() + " " + label);
            }
            assertTrue(user.contains("You MUST return exactly 1 suggestion(s), one for each asOf date: 2026-07-01."));
            assertTrue(user.endsWith("{\"ticker\":\"QQQ\"}\n"));
        }
        assertFalse(GeminiPrompts.forVersion("v2").systemInstruction().contains("categorize the asset as"));
        assertNotEquals(GeminiPrompts.forVersion("v1").userPrompt(1, "d", "{}"),
                GeminiPrompts.forVersion("v5").userPrompt(1, "d", "{}"));
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
