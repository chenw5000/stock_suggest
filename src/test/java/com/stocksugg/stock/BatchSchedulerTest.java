package com.stocksugg.stock;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchSchedulerTest {

    @Test
    void shouldFireOnlyOnceAtOrAfterRefreshMinute() {
        LocalDate day = LocalDate.of(2026, 7, 22);
        LocalTime refresh = LocalTime.of(16, 5);

        assertFalse(BatchScheduler.shouldFire(null, day, LocalTime.of(16, 4), refresh));
        assertTrue(BatchScheduler.shouldFire(null, day, LocalTime.of(16, 5), refresh));
        assertTrue(BatchScheduler.shouldFire(null, day, LocalTime.of(16, 6), refresh));
        assertFalse(BatchScheduler.shouldFire(day, day, LocalTime.of(16, 6), refresh));
        assertTrue(BatchScheduler.shouldFire(day.minusDays(1), day, LocalTime.of(16, 5), refresh));
    }

    @Test
    void tickMarksDayAsFiredWithoutReentry() {
        BatchScheduler scheduler = new BatchScheduler();
        LocalTime refresh = LocalTime.of(16, 5);
        ZonedDateTime atRefresh = ZonedDateTime.of(
                LocalDate.of(2026, 7, 22),
                LocalTime.of(16, 5),
                ZoneId.of("America/Los_Angeles"));

        assertTrue(scheduler.shouldAttempt(atRefresh, refresh));
        // Simulate the same-day guard the real tick applies after firing.
        scheduler.markFired(atRefresh.toLocalDate());
        assertFalse(scheduler.shouldAttempt(atRefresh.plusMinutes(1), refresh));
    }
}
