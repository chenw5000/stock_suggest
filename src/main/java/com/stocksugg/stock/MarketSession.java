package com.stocksugg.stock;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * US regular-session helpers using Pacific Time.
 * Daily bars are treated as complete at/after admin {@link RefreshTime#ADMIN_KEY}
 * (default {@link RefreshTime#DEFAULT} PT).
 */
public final class MarketSession {

    public static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");

    /** @deprecated Prefer {@link RefreshTime#DEFAULT}; kept for callers that still reference it. */
    @Deprecated
    public static final LocalTime REGULAR_CLOSE_PACIFIC = RefreshTime.DEFAULT;

    private MarketSession() {}

    /**
     * Latest calendar date whose daily bar is considered closed for download/persist.
     * Before {@link RefreshTime#ADMIN_KEY} on a calendar day (Pacific), returns yesterday.
     */
    public static LocalDate lastCompleteSessionDate() {
        return lastCompleteSessionDate(ZonedDateTime.now(PACIFIC), RefreshTime.loadOrDefault());
    }

    static LocalDate lastCompleteSessionDate(ZonedDateTime nowPacific) {
        return lastCompleteSessionDate(nowPacific, RefreshTime.DEFAULT);
    }

    static LocalDate lastCompleteSessionDate(ZonedDateTime nowPacific, LocalTime refreshTime) {
        LocalTime cutoff = refreshTime == null ? RefreshTime.DEFAULT : refreshTime;
        LocalDate today = nowPacific.toLocalDate();
        if (nowPacific.toLocalTime().isBefore(cutoff)) {
            return today.minusDays(1);
        }
        return today;
    }
}
