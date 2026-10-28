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

    /**
     * v1 text must stay stable unless intentionally revised (last revised when dataUsed was
     * told to list volume / vol20 / volumeVsVol20Pct when those fields influenced the call).
     */
    @Test
    void v1IsFrozen() throws Exception {
        GeminiPrompts.Prompt v1 = GeminiPrompts.forVersion("v1");
        assertEquals("203f471bbd33dde899d48c04eb34a7d945b11d1188e944cd21e9d6718264c6eb",
                sha256(v1.systemInstruction()));
        assertEquals("c01ca2783cafaf2eeb27ead4c0756f1111594fc6c350c133d2fdee21d72695bf",
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
