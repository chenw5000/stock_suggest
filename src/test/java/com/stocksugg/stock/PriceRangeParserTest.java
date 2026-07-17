package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceRangeParserTest {

    @Test
    void parsesMidpoint() {
        assertEquals(305f, PriceRangeParser.midpoint("300 - 310").orElseThrow(), 0.001f);
        assertEquals(291f, PriceRangeParser.midpoint("290-292").orElseThrow(), 0.001f);
    }

    @Test
    void normalizesAction() {
        assertEquals("BUY", PriceRangeParser.normalizeAction("buy"));
        assertEquals("AVOID", PriceRangeParser.normalizeAction(null));
    }
}
