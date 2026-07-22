package com.stocksugg.stock;

import com.stocksugg.App;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background monitor: once per day at/after admin {@link RefreshTime#ADMIN_KEY} (Pacific),
 * starts {@link App#startBatchJobAsync()} (Yahoo refresh + Gemini suggestions).
 */
public final class BatchScheduler implements Runnable {

    private static final long POLL_MILLIS = 15_000L;

    private static final Object LOCK = new Object();
    private static volatile Thread worker;
    private static volatile BatchScheduler active;

    private final AtomicBoolean stop = new AtomicBoolean(false);
    private volatile LocalDate lastFiredDate;

    BatchScheduler() {}

    /** Starts the daemon monitor if it is not already running. */
    public static void start() {
        synchronized (LOCK) {
            if (worker != null && worker.isAlive()) {
                return;
            }
            LocalTime refreshAt = RefreshTime.ensurePresent();
            active = new BatchScheduler();
            // If the JVM starts after today's refresh minute, do not catch up immediately.
            ZonedDateTime now = ZonedDateTime.now(MarketSession.PACIFIC);
            if (!now.toLocalTime().withSecond(0).withNano(0)
                    .isBefore(refreshAt.withSecond(0).withNano(0))) {
                active.lastFiredDate = now.toLocalDate();
            }
            worker = new Thread(active, "stocksugg-batch-scheduler");
            worker.setDaemon(true);
            worker.start();
            System.out.println("Batch scheduler started; daily refresh at "
                    + RefreshTime.format(refreshAt) + " America/Los_Angeles.");
        }
    }

    /** Requests a clean stop of the monitor thread (used by Tomcat undeploy / tests). */
    public static void stop() {
        synchronized (LOCK) {
            BatchScheduler current = active;
            Thread thread = worker;
            if (current != null) {
                current.stop.set(true);
            }
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(2_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            active = null;
            worker = null;
            System.out.println("Batch scheduler stopped.");
        }
    }

    public static boolean isRunning() {
        Thread thread = worker;
        return thread != null && thread.isAlive();
    }

    @Override
    public void run() {
        while (!stop.get()) {
            try {
                tick(ZonedDateTime.now(MarketSession.PACIFIC), RefreshTime.loadOrDefault());
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Batch scheduler tick failed: " + e.getMessage());
                e.printStackTrace();
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /** Package-visible for unit tests. Returns true when a batch start was attempted. */
    boolean tick(ZonedDateTime nowPacific, LocalTime refreshAt) {
        if (!shouldAttempt(nowPacific, refreshAt)) {
            return false;
        }
        LocalDate today = nowPacific.toLocalDate();
        markFired(today);
        LocalTime refreshMinute = (refreshAt == null ? RefreshTime.DEFAULT : refreshAt)
                .withSecond(0).withNano(0);
        System.out.println("REFRESH_TIME reached (" + RefreshTime.format(refreshMinute)
                + " PT on " + today + "); starting batch job…");
        boolean started = App.startBatchJobAsync();
        if (!started) {
            System.out.println("Scheduled batch skipped: a batch job is already running.");
        }
        return true;
    }

    boolean shouldAttempt(ZonedDateTime nowPacific, LocalTime refreshAt) {
        LocalTime cutoff = refreshAt == null ? RefreshTime.DEFAULT : refreshAt;
        return shouldFire(
                lastFiredDate,
                nowPacific.toLocalDate(),
                nowPacific.toLocalTime().withSecond(0).withNano(0),
                cutoff.withSecond(0).withNano(0));
    }

    void markFired(LocalDate day) {
        lastFiredDate = day;
    }

    static boolean shouldFire(
            LocalDate lastFiredDate,
            LocalDate today,
            LocalTime nowMinute,
            LocalTime refreshMinute) {
        if (today.equals(lastFiredDate)) {
            return false;
        }
        return !nowMinute.isBefore(refreshMinute);
    }
}
