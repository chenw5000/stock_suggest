package com.stocksugg;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppTest {

    @Test
    void splitsBackfillIntervalByCalendarMonth() {
        List<App.DateRange> ranges = App.monthlyRanges(
                LocalDate.parse("2026-01-15"),
                LocalDate.parse("2026-03-10"));

        assertEquals(List.of(
                new App.DateRange(
                        LocalDate.parse("2026-01-15"),
                        LocalDate.parse("2026-01-31")),
                new App.DateRange(
                        LocalDate.parse("2026-02-01"),
                        LocalDate.parse("2026-02-28")),
                new App.DateRange(
                        LocalDate.parse("2026-03-01"),
                        LocalDate.parse("2026-03-10"))),
                ranges);
    }

    @Test
    void keepsSingleMonthAsOneInterval() {
        assertEquals(
                List.of(new App.DateRange(
                        LocalDate.parse("2026-07-01"),
                        LocalDate.parse("2026-07-20"))),
                App.monthlyRanges(
                        LocalDate.parse("2026-07-01"),
                        LocalDate.parse("2026-07-20")));
    }

    @Test
    void rejectsReversedBackfillInterval() {
        assertThrows(
                IllegalArgumentException.class,
                () -> App.monthlyRanges(
                        LocalDate.parse("2026-07-20"),
                        LocalDate.parse("2026-01-01")));
    }
}
