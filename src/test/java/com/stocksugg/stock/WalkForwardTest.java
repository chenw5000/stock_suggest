package com.stocksugg.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocksugg.db.Database;
import com.stocksugg.db.WalkForwardRepository;
import com.stocksugg.web.SuggestApi;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkForwardTest {

    @Test
    void periodsAreConsecutiveWithTrailingTraining() {
        List<WalkForward.Period> quarterly = WalkForward.periods(
                WalkForward.Cadence.QUARTERLY, LocalDate.parse("2026-01-04"), LocalDate.parse("2026-10-02"));
        assertEquals(3, quarterly.size());
        WalkForward.Period first = quarterly.get(0);
        assertEquals(LocalDate.parse("2025-10-04"), first.trainFrom());
        assertEquals(LocalDate.parse("2026-01-03"), first.trainTo());
        assertEquals(LocalDate.parse("2026-04-03"), first.testTo());
        assertEquals(LocalDate.parse("2026-04-04"), quarterly.get(1).testFrom());
        assertEquals(LocalDate.parse("2026-01-04"), quarterly.get(1).trainFrom());
        assertEquals(LocalDate.parse("2026-10-02"), quarterly.get(2).testTo());

        List<WalkForward.Period> weekly = WalkForward.periods(
                WalkForward.Cadence.WEEKLY, LocalDate.parse("2026-09-05"), LocalDate.parse("2026-10-02"));
        assertEquals(4, weekly.size());
        assertEquals(LocalDate.parse("2026-09-11"), weekly.get(0).testTo());
        assertEquals(LocalDate.parse("2026-09-26"), weekly.get(3).testFrom());

        List<WalkForward.Period> folded = WalkForward.periods(
                WalkForward.Cadence.QUARTERLY, LocalDate.parse("2026-04-02"), LocalDate.parse("2026-10-02"));
        assertEquals(2, folded.size());
        assertEquals(LocalDate.parse("2026-07-02"), folded.get(1).testFrom());
        assertEquals(LocalDate.parse("2026-10-02"), folded.get(1).testTo());
    }

    @Test
    void simulateFromCashMatchesRun() {
        List<BacktestDay> days = randomDays(new Random(7), 120);
        for (BacktestStrategy strategy : List.of(
                BacktestStrategy.allIn(), BacktestStrategy.equalParts(3), BacktestStrategy.equalParts(4))) {
            SuggestionBacktester.Result run = SuggestionBacktester.run(10_000, strategy, days);
            SuggestionBacktester.Segment segment = SuggestionBacktester.simulate(
                    SuggestionBacktester.Position.ofCash(10_000), 10_000.0 / strategy.parts(), strategy, days);
            assertEquals(run.endingCash(), segment.end().cash(), 1e-9);
            assertEquals(run.endingShares(), segment.end().shares());
            assertEquals(run.endingEquity(), segment.dailyEquity()[days.size() - 1], 1e-6);
            assertEquals(run.trades(), segment.trades());
            assertEquals(days.size(), segment.dailyShares().length);
        }
    }

    @Test
    void relotSplitsSharesByCurrentPartSize() {
        SuggestionBacktester.Position held = new SuggestionBacktester.Position(5_000, List.of(50));
        assertEquals(List.of(25, 25),
                SuggestionBacktester.relot(held, 100, 4).lots());
        assertEquals(List.of(50), SuggestionBacktester.relot(held, 100, 1).lots());
        assertEquals(5_000, SuggestionBacktester.relot(held, 100, 4).cash(), 1e-9);

        SuggestionBacktester.Position small = new SuggestionBacktester.Position(9_900, List.of(1));
        assertEquals(List.of(1), SuggestionBacktester.relot(small, 100, 4).lots());
        assertEquals(List.of(),
                SuggestionBacktester.relot(SuggestionBacktester.Position.ofCash(10_000), 100, 4).lots());
    }

    @Test
    void carriedSharesAreSoldInTheNextPeriod() {
        LocalDate d = LocalDate.parse("2026-04-06");
        List<BacktestDay> days = List.of(
                new BacktestDay(d, 110f, "HOLD", 0.7f),
                new BacktestDay(d.plusDays(1), 120f, "SELL", 0.7f));
        SuggestionBacktester.Segment segment = SuggestionBacktester.simulate(
                new SuggestionBacktester.Position(0, List.of(100)), 10_000, BacktestStrategy.allIn(), days);
        assertEquals(0, segment.end().shares());
        assertEquals(12_000, segment.end().cash(), 1e-9);
        assertArrayEquals(new double[] {11_000, 12_000}, segment.dailyEquity(), 1e-9);
        assertArrayEquals(new int[] {100, 0}, segment.dailyShares());
    }

    @Test
    void runCarriesPositionsAcrossPeriodsAndSavesReport() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:walk_forward;DB_CLOSE_DELAY=-1")) {
            insertSeries(db, "GOOD", LocalDate.parse("2025-09-01"), LocalDate.parse("2026-10-02"));
            insertSeries(db, "LATE", LocalDate.parse("2026-05-01"), LocalDate.parse("2026-10-02"));
            LocalDate from = LocalDate.parse("2026-01-05");
            LocalDate to = LocalDate.parse("2026-10-02");

            WalkForward.Report report = WalkForward.run(db, List.of("GOOD", "LATE"),
                    WalkForward.Cadence.QUARTERLY, from, to, 1L, false, null);

            assertEquals(2, report.tickersTotal());
            assertEquals(1, report.included());
            assertEquals(3, report.periods().size());
            WalkForward.TickerResult good = report.tickers().get(0);
            assertTrue(good.ok());
            assertFalse(report.tickers().get(1).ok());

            assertEquals(3, good.periods().size());
            for (int i = 0; i < good.periods().size(); i++) {
                WalkForward.PeriodResult p = good.periods().get(i);
                assertEquals(WalkForward.PERIOD_NEW, p.status());
                assertFalse(p.cached());
                if (i > 0) {
                    assertEquals(good.periods().get(i - 1).endEquity(), p.startEquity(), 0.01);
                }
            }
            assertEquals(10_000, good.periods().get(0).startEquity(), 1e-9);
            assertEquals(good.dates().size(), good.walkForwardEquity().size());
            assertEquals(good.dates().size(), good.buyHoldEquity().size());
            assertEquals(good.walkForwardEquity().getLast(), good.walkForward().endingEquity(), 0.01);
            assertEquals(good.periods().getLast().endEquity(), good.walkForward().endingEquity(), 0.01);
            assertEquals(100.0, good.buyHold().timeInMarketPct(), 1e-9);

            WalkForward.Portfolio portfolio = report.portfolio();
            assertEquals(10_000, portfolio.invested(), 1e-9);
            assertEquals(good.walkForward().endingEquity(), portfolio.walkForwardMetrics().endingEquity(), 0.01);
            assertEquals(good.buyHold().endingEquity(), portfolio.buyHoldMetrics().endingEquity(), 0.01);

            WalkForward.Report again = WalkForward.run(db, List.of("GOOD"),
                    WalkForward.Cadence.QUARTERLY, from, to, 1L, false, null);
            WalkForward.TickerResult reused = again.tickers().get(0);
            assertTrue(reused.periods().stream().allMatch(p -> Boolean.TRUE.equals(p.cached())));
            assertEquals(good.walkForward().endingEquity(), reused.walkForward().endingEquity(), 1e-9);

            String json = SuggestApi.mapper().writeValueAsString(report);
            WalkForwardRepository repo = new WalkForwardRepository(db);
            WalkForwardRepository.RunSummary summary = new WalkForwardRepository.RunSummary(
                    0, report.cadence(), from, to, 1L, 2, 1,
                    portfolio.walkForwardMetrics().returnPct(), portfolio.buyHoldMetrics().returnPct(),
                    portfolio.fixedMetrics().returnPct(), report.durationMs(), null);
            long id1 = repo.save(summary, json);
            long id2 = repo.save(summary, json);
            assertEquals(1, repo.list(1L).size());
            WalkForwardRepository.StoredRun stored = repo.findLatest(1L).orElseThrow();
            assertEquals(id2, stored.summary().id());
            assertTrue(repo.find(id1).isEmpty());
            JsonNode node = SuggestApi.mapper().readTree(stored.resultJson());
            assertEquals("2026-01-05", node.path("testFrom").asText());
            assertEquals("GOOD", node.path("tickers").get(0).path("ticker").asText());
        }
    }

    private static List<BacktestDay> randomDays(Random random, int count) {
        String[] actions = {"BUY", "SELL", "HOLD", "AVOID", null};
        List<BacktestDay> days = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-05");
        float close = 100f;
        for (int i = 0; i < count; i++) {
            close = Math.max(5f, close * (float) (1 + (random.nextDouble() - 0.5) * 0.06));
            String action = actions[random.nextInt(actions.length)];
            String avoidSide = "AVOID".equals(action) ? (random.nextBoolean() ? "HIGH" : "LOW") : null;
            days.add(new BacktestDay(d.plusDays(i), close, action, (float) random.nextDouble(), avoidSide));
        }
        return days;
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
