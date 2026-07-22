package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketSessionTest {

    private static final LocalTime REFRESH = LocalTime.of(16, 0);

    @Test
    void beforeCloseUsesYesterday() {
        ZonedDateTime beforeClose = ZonedDateTime.of(
                LocalDate.of(2026, 7, 15),
                LocalTime.of(15, 59),
                ZoneId.of("America/Los_Angeles"));
        assertEquals(
                LocalDate.of(2026, 7, 14),
                MarketSession.lastCompleteSessionDate(beforeClose, REFRESH));
    }

    @Test
    void atOrAfterCloseUsesToday() {
        ZonedDateTime atClose = ZonedDateTime.of(
                LocalDate.of(2026, 7, 15),
                LocalTime.of(16, 0),
                ZoneId.of("America/Los_Angeles"));
        assertEquals(
                LocalDate.of(2026, 7, 15),
                MarketSession.lastCompleteSessionDate(atClose, REFRESH));

        ZonedDateTime afterClose = ZonedDateTime.of(
                LocalDate.of(2026, 7, 15),
                LocalTime.of(16, 30),
                ZoneId.of("America/Los_Angeles"));
        assertEquals(
                LocalDate.of(2026, 7, 15),
                MarketSession.lastCompleteSessionDate(afterClose, REFRESH));
    }
}
