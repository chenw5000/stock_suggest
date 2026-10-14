package com.stocksugg.stock;

import com.stocksugg.db.Database;
import com.stocksugg.db.PerformanceRepository;
import com.stocksugg.db.StrategyOptimizeRepository;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyPerformanceTest {

    @Test
    void weekEndingMapsEachDayToItsFriday() {
        LocalDate friday = LocalDate.parse("2026-10-02");
        assertEquals(friday, WeeklyPerformance.weekEnding(LocalDate.parse("2026-09-28")));
        assertEquals(friday, WeeklyPerformance.weekEnding(friday));
        assertEquals(friday, WeeklyPerformance.weekEnding(LocalDate.parse("2026-10-03")));
        assertEquals(friday, WeeklyPerformance.weekEnding(LocalDate.parse("2026-10-04")));
        assertEquals(friday.plusWeeks(1), WeeklyPerformance.weekEnding(LocalDate.parse("2026-10-05")));
    }

    @Test
    void latestCompleteWeekWaitsForFridaySession() {
        LocalDate friday = LocalDate.parse("2026-10-02");
        assertEquals(friday.minusWeeks(1),
                WeeklyPerformance.latestCompleteWeekEnding(LocalDate.parse("2026-10-01")));
        assertEquals(friday, WeeklyPerformance.latestCompleteWeekEnding(friday));
        assertEquals(friday, WeeklyPerformance.latestCompleteWeekEnding(LocalDate.parse("2026-10-03")));
    }

    @Test
    void windowsDoNotOverlap() {
        WeeklyPerformance.Windows w = WeeklyPerformance.windowsFor(
                LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-02"));
        assertEquals(LocalDate.parse("2026-04-03"), w.trainFrom());
        assertEquals(LocalDate.parse("2026-07-02"), w.trainTo());
        assertEquals(LocalDate.parse("2026-07-03"), w.testFrom());
        assertEquals(LocalDate.parse("2026-10-02"), w.testTo());
    }

    @Test
    void coverageRequiresLabeledDaysFromTheWindowStart() {
        LocalDate start = LocalDate.parse("2026-07-03");
        List<BacktestDay> full = new ArrayList<>();
        List<BacktestDay> sparse = new ArrayList<>();
        int n = 0;
        for (LocalDate d = start; d.isBefore(start.plusMonths(3)); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            full.add(new BacktestDay(d, 100f, "HOLD", 0.5f));
            sparse.add(new BacktestDay(d, 100f, n++ % 2 == 0 ? "HOLD" : null, 0.5f));
        }
        assertNull(WeeklyPerformance.coverageProblem(full, start, "test"));
        assertNotNull(WeeklyPerformance.coverageProblem(sparse, start, "test"));
        assertNotNull(WeeklyPerformance.coverageProblem(full.subList(10, full.size()), start, "test"));
        assertNotNull(WeeklyPerformance.coverageProblem(List.of(), start, "test"));
    }

    @Test
    void evaluateAppliesTrainingBestToTestWindowAndReusesSavedStrategy() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:weekly_performance;DB_CLOSE_DELAY=-1")) {
            insertSeries(db, "GOOD", LocalDate.parse("2026-03-02"), LocalDate.parse("2026-10-02"));
            insertSeries(db, "NEW", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-02"));
            LocalDate weekEnding = LocalDate.parse("2026-10-02");

            WeeklyPerformance.Evaluation first = WeeklyPerformance.evaluate(
                    db, weekEnding, 1L, List.of("GOOD", "NEW"), false,
                    PerformanceWeek.TRIGGER_MANUAL, null);

            PerformanceWeek week = first.week();
            assertEquals(weekEnding, week.asOfDate());
            assertEquals(LocalDate.parse("2026-07-02"), week.trainTo());
            assertEquals(2, week.tickersTotal());
            assertEquals(1, week.evaluated());
            assertEquals(1, week.skipped());
            assertEquals(10_000.0, week.invested(), 1e-9);
            assertEquals(week.evaluated(), week.beatCount() + week.notBeatCount());

            PerformanceTickerResult good = first.rows().get(0);
            assertTrue(good.ok());
            assertFalse(good.strategyCached());
            assertEquals(week.strategyTotal(), good.strategyEquity(), 1e-9);
            assertEquals(week.buyHoldTotal(), good.buyHoldEquity(), 1e-9);
            assertEquals(good.strategyEquity() > good.buyHoldEquity() + 0.005, good.beat());
            assertTrue(new StrategyOptimizeRepository(db).findExactBest(
                    "GOOD", 1L, week.trainFrom(), week.trainTo(), 10_000.0).isPresent());

            PerformanceTickerResult fresh = first.rows().get(1);
            assertEquals(PerformanceTickerResult.STATUS_SKIPPED, fresh.status());
            assertTrue(fresh.skipReason().startsWith("insufficient data"));

            WeeklyPerformance.Evaluation second = WeeklyPerformance.evaluate(
                    db, weekEnding, 1L, List.of("GOOD", "NEW"), false,
                    PerformanceWeek.TRIGGER_SCHEDULED, null);
            PerformanceTickerResult reused = second.rows().get(0);
            assertTrue(reused.strategyCached());
            assertEquals(good.strategy(), reused.strategy());
            assertEquals(good.strategyEquity(), reused.strategyEquity(), 1e-9);

            PerformanceRepository repo = new PerformanceRepository(db);
            PerformanceWeek saved = repo.save(first.week(), first.rows());
            repo.save(second.week(), second.rows());
            assertEquals(1, repo.listWeeks(1L).size());
            PerformanceWeek loaded = repo.findLatestWeek(1L).orElseThrow();
            assertEquals(PerformanceWeek.TRIGGER_SCHEDULED, loaded.triggerSource());
            assertEquals(saved.weekEnding(), loaded.weekEnding());
            List<PerformanceTickerResult> rows = repo.findTickers(loaded.id());
            assertEquals(2, rows.size());
            assertEquals("GOOD", rows.get(0).ticker());
            assertEquals(good.strategy(), rows.get(0).strategy());
            assertEquals(good.beat(), rows.get(0).beat());
            assertNull(rows.get(1).strategy());
        }
    }

    /** Weekday closes on a sine wave, BUY near the trough and SELL near the peak, HOLD otherwise. */
    private static void insertSeries(Database db, String ticker, LocalDate from, LocalDate to)
            throws Exception {
        int i = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            double phase = Math.sin(i * 2 * Math.PI / 20.0);
            float close = (float) (100 + i * 0.1 + 10 * phase);
            String action = phase < -0.8 ? "BUY" : phase > 0.8 ? "SELL" : "HOLD";
            long stockId;
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "INSERT INTO stock (ticker, \"date\", close, rsi14, ma20) VALUES (?, ?, ?, 50, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, ticker);
                ps.setString(2, d.toString());
                ps.setFloat(3, close);
                ps.setFloat(4, close);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    stockId = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "INSERT INTO suggestions (stock_id, param_id, suggestedAction, confidence) VALUES (?, 1, ?, 0.7)")) {
                ps.setLong(1, stockId);
                ps.setString(2, action);
                ps.executeUpdate();
            }
            i++;
        }
    }
}
