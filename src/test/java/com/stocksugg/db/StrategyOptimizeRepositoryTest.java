package com.stocksugg.db;

import com.stocksugg.stock.BacktestStrategy;
import com.stocksugg.stock.StrategyOptimize;
import com.stocksugg.stock.SuggestionBacktester;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyOptimizeRepositoryTest {

    @Test
    void upsertInsertsAndUpdatesByNaturalKey() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:strategy_optimize_upsert;DB_CLOSE_DELAY=-1")) {
            assertTrue(db.strategyOptimizeTableExists());

            StrategyOptimizeRepository repo = new StrategyOptimizeRepository(db);
            LocalDate from = LocalDate.parse("2026-01-01");
            LocalDate to = LocalDate.parse("2026-07-01");

            BacktestStrategy strategy = new BacktestStrategy(
                    4, 0.55, 0.60,
                    BacktestStrategy.TradeIntent.BUY_PART,
                    BacktestStrategy.TradeIntent.SELL_ALL,
                    BacktestStrategy.TradeIntent.NONE,
                    BacktestStrategy.TradeIntent.SELL_PART);

            SuggestionBacktester.Result result = new SuggestionBacktester.Result(
                    10_000.0, 2_500.0, 50, 150.0, 10_000.0, 0.0,
                    3, 2, 1, List.of());
            SuggestionBacktester.Result buyHold = new SuggestionBacktester.Result(
                    10_000.0, 0.0, 70, 140.0, 9_800.0, -2.0,
                    1, 0, 0, List.of());

            StrategyOptimize first = StrategyOptimize.fromSearch(
                    "aapl", 1L, from, to, 10_000.0, strategy, result, buyHold, 1);
            assertEquals(1, repo.upsert(first));

            Optional<StrategyOptimize> loaded = repo.findByKey("AAPL", 1L, from, to, 1);
            assertTrue(loaded.isPresent());
            assertEquals("AAPL", loaded.get().ticker());
            assertEquals(4, loaded.get().parts());
            assertEquals(0.55, loaded.get().minBuyConfidence(), 1e-9);
            assertEquals("BUY_PART", loaded.get().onBuy());
            assertEquals("SELL_ALL", loaded.get().onSell());
            assertEquals(10_000.0, loaded.get().endingEquity(), 1e-9);
            assertEquals(-2.0, loaded.get().buyHoldReturnPct(), 1e-9);
            assertEquals(strategy, loaded.get().toStrategy());

            SuggestionBacktester.Result better = new SuggestionBacktester.Result(
                    10_000.0, 1_000.0, 80, 160.0, 13_800.0, 38.0,
                    5, 1, 0, List.of());
            StrategyOptimize updated = StrategyOptimize.fromSearch(
                    "AAPL", 1L, from, to, 10_000.0, strategy, better, buyHold, 1);
            assertEquals(1, repo.upsert(updated));

            loaded = repo.findByKey("AAPL", 1L, from, to, 1);
            assertTrue(loaded.isPresent());
            assertEquals(13_800.0, loaded.get().endingEquity(), 1e-9);
            assertEquals(38.0, loaded.get().returnPct(), 1e-9);
            assertEquals(1, repo.findByWindow("AAPL", 1L, from, to).size());
            assertTrue(repo.findLatestBest("AAPL", 1L).isPresent());
            assertEquals(1, repo.findBestForToDate("AAPL", 1L, to).size());

            assertTrue(repo.findBestNearWindow(
                    "AAPL", 1L, from.plusDays(3), to.minusDays(2), 7).isPresent());
            assertTrue(repo.findBestNearWindow(
                    "AAPL", 1L, from.plusDays(14), to, 7).isEmpty());
        }
    }
}
