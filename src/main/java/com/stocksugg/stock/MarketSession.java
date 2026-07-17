package com.stocksugg.stock;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * US regular-session helpers using Pacific Time.
 * Daily bars are treated as complete at/after 4:00 PM PT (7:00 PM ET close).
 */
public final class MarketSession {

    public static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    public static final LocalTime REGULAR_CLOSE_PACIFIC = LocalTime.of(16, 0);

    private MarketSession() {}

    /**
     * Latest calendar date whose daily bar is considered closed for download/persist.
     * Before 1:00 PM PT on a calendar day, returns yesterday.
     */
    public static LocalDate lastCompleteSessionDate() {
        return lastCompleteSessionDate(ZonedDateTime.now(PACIFIC));
    }

    static LocalDate lastCompleteSessionDate(ZonedDateTime nowPacific) {
        LocalDate today = nowPacific.toLocalDate();
        if (nowPacific.toLocalTime().isBefore(REGULAR_CLOSE_PACIFIC)) {
            return today.minusDays(1);
        }
        return today;
    }
}
