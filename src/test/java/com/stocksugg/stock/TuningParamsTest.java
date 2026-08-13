package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuningParamsTest {

    @Test
    void indicatorWeightsUsePayloadFieldNamesAndDefaults() {
        TuningParams params = new TuningParams(
                3L, 20,
                2, null, 1, 3, 1,
                5, 0, 4,
                "WEIGHTED", "test");
        Map<String, Integer> weights = params.indicatorWeights();

        assertEquals(2, weights.get("ma5"));
        assertEquals(1, weights.get("ma10")); // null → 1
        assertEquals(1, weights.get("ma20"));
        assertEquals(3, weights.get("ma50"));
        assertEquals(1, weights.get("ma200"));
        assertEquals(5, weights.get("rsi14"));
        assertEquals(0, weights.get("chandeMmt"));
        assertEquals(4, weights.get("chalkinMF"));
        assertTrue(TuningParams.WEIGHT_MEANING.contains("0 = ignore"));
    }
}
