package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketSessionTest {

    @Test
    void beforeCloseUsesYesterday() {
        ZonedDateTime beforeClose = ZonedDateTime.of(
                LocalDate.of(2026, 7, 15),
                LocalTime.of(12, 59),
                ZoneId.of("America/Los_Angeles"));
        assertEquals(LocalDate.of(2026, 7, 14), MarketSession.lastCompleteSessionDate(beforeClose));
    }

    @Test
    void atOrAfterCloseUsesToday() {
        ZonedDateTime atClose = ZonedDateTime.of(
                LocalDate.of(2026, 7, 15),
                LocalTime.of(13, 0),
                ZoneId.of("America/Los_Angeles"));
        assertEquals(LocalDate.of(2026, 7, 15), MarketSession.lastCompleteSessionDate(atClose));

        ZonedDateTime afterClose = ZonedDateTime.of(
                LocalDate.of(2026, 7, 15),
                LocalTime.of(15, 30),
                ZoneId.of("America/Los_Angeles"));
        assertEquals(LocalDate.of(2026, 7, 15), MarketSession.lastCompleteSessionDate(afterClose));
    }
}
