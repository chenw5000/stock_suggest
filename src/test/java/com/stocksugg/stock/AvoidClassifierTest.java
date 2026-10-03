package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AvoidClassifierTest {

    @Test
    void highWhenRsiOrStretchExceedsThreshold() {
        assertEquals(AvoidClassifier.HIGH, AvoidClassifier.side("AVOID", 60f, 100f, 100f));
        assertEquals(AvoidClassifier.HIGH, AvoidClassifier.side("avoid", 40f, 106f, 100f));
        assertEquals(AvoidClassifier.HIGH, AvoidClassifier.side("AVOID", null, 106f, 100f));
        assertEquals(AvoidClassifier.HIGH, AvoidClassifier.side("AVOID", 70f, null, null));
    }

    @Test
    void lowWhenIndicatorsPresentButNotStretched() {
        assertEquals(AvoidClassifier.LOW, AvoidClassifier.side("AVOID", 59f, 104f, 100f));
        assertEquals(AvoidClassifier.LOW, AvoidClassifier.side("AVOID", 30f, null, null));
        assertEquals(AvoidClassifier.LOW, AvoidClassifier.side("AVOID", null, 90f, 100f));
    }

    @Test
    void nullForOtherActionsOrMissingIndicators() {
        assertNull(AvoidClassifier.side("BUY", 80f, 120f, 100f));
        assertNull(AvoidClassifier.side(null, 80f, 120f, 100f));
        assertNull(AvoidClassifier.side("AVOID", null, 100f, null));
    }

    @Test
    void actionKeySplitsOnlyClassifiedAvoid() {
        assertEquals("AVOID_HIGH", AvoidClassifier.actionKey("AVOID", AvoidClassifier.HIGH));
        assertEquals("AVOID_LOW", AvoidClassifier.actionKey("avoid", AvoidClassifier.LOW));
        assertEquals("AVOID", AvoidClassifier.actionKey("AVOID", null));
        assertEquals("BUY", AvoidClassifier.actionKey("buy", null));
        assertNull(AvoidClassifier.actionKey(null, null));
    }
}
