package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RefreshTimeTest {

    @Test
    void parseAcceptsHhMm() {
        assertEquals(LocalTime.of(16, 5), RefreshTime.parse("16:05"));
        assertEquals(LocalTime.of(9, 0), RefreshTime.parse("9:00"));
        assertEquals(RefreshTime.DEFAULT, RefreshTime.parse(""));
        assertEquals(RefreshTime.DEFAULT, RefreshTime.parse(null));
    }

    @Test
    void parseRejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> RefreshTime.parse("4pm"));
        assertThrows(IllegalArgumentException.class, () -> RefreshTime.parse("25:00"));
    }

    @Test
    void formatPadsHour() {
        assertEquals("09:05", RefreshTime.format(LocalTime.of(9, 5)));
        assertEquals("16:00", RefreshTime.format(RefreshTime.DEFAULT));
    }
}
