package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringListCodecTest {

    @Test
    void encodeProducesJsonArray() {
        assertEquals("[\"first\",\"second\"]", StringListCodec.encode(List.of("first", "second")));
        assertNull(StringListCodec.encode(null));
        assertNull(StringListCodec.encode(List.of()));
        assertNull(StringListCodec.encode(List.of("  ", "")));
    }

    @Test
    void decodeJsonArray() {
        assertEquals(List.of("a", "b"), StringListCodec.decode("[\"a\",\"b\"]"));
        assertTrue(StringListCodec.decode(null).isEmpty());
        assertTrue(StringListCodec.decode("[]").isEmpty());
    }

    @Test
    void decodeLegacyNewlines() {
        assertEquals(List.of("line one", "line two"), StringListCodec.decode("line one\nline two"));
    }

    @Test
    void roundTrip() {
        List<String> original = List.of("Price above MA50", "Volume confirming");
        assertEquals(original, StringListCodec.decode(StringListCodec.encode(original)));
    }
}
