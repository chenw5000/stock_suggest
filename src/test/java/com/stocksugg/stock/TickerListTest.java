package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TickerListTest {

    @Test
    void parsesCommaSeparatedTickers() {
        List<String> tickers = TickerList.parse("aapl, TSLA, msft");
        assertEquals(List.of("AAPL", "TSLA", "MSFT"), tickers);
    }

    @Test
    void acceptsExactlyMaxTickers() {
        StringBuilder raw = new StringBuilder("T01");
        for (int i = 2; i <= TickerList.MAX_TICKERS; i++) {
            raw.append(",T").append(String.format("%02d", i));
        }
        List<String> tickers = TickerList.parse(raw.toString());
        assertEquals(TickerList.MAX_TICKERS, tickers.size());
        assertEquals(100, TickerList.MAX_TICKERS);
    }

    @Test
    void rejectsMoreThanMaxTickers() {
        StringBuilder raw = new StringBuilder("T01");
        for (int i = 2; i <= TickerList.MAX_TICKERS + 1; i++) {
            raw.append(",T").append(String.format("%02d", i));
        }
        assertThrows(IllegalArgumentException.class, () -> TickerList.parse(raw.toString()));
    }

    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> TickerList.parse("  , , "));
    }
}
