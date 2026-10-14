package com.stocksugg.stock;

import com.stocksugg.db.Database;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptExperimentTest {

    @Test
    void setupCreatesOneRowPerVersionOnce() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:prompt_setup;DB_CLOSE_DELAY=-1")) {
            Map<String, TuningParams> first = PromptExperiment.setup(db, 1L);
            Map<String, TuningParams> again = PromptExperiment.setup(db, 1L);
            assertEquals(List.of("v1", "v2", "v3", "v4", "v5"), List.copyOf(first.keySet()));
            assertEquals(first, again);
            first.forEach((version, row) -> {
                assertEquals(version, row.promptVersion());
                assertEquals("PROMPT-" + version, row.name());
                assertEquals(40, row.lookbackBars());
            });
        }
    }

    @Test
    void createReusesMatchingRowAndRejectsConflictingOne() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:prompt_create;DB_CLOSE_DELAY=-1")) {
            TuningParams cold = PromptExperiment.create(db, 1L, "v3", 0.0, "PROMPT-v3-t0-a");
            assertEquals("v3", cold.promptVersion());
            assertEquals(0.0, cold.temperature());
            assertEquals(cold, PromptExperiment.create(db, 1L, "v3", 0.0, "PROMPT-v3-t0-a"));
            assertThrows(IllegalArgumentException.class,
                    () -> PromptExperiment.create(db, 1L, "v3", null, "PROMPT-v3-t0-a"));
            assertThrows(IllegalArgumentException.class,
                    () -> PromptExperiment.create(db, 1L, "v1", 0.0, "PROMPT-v3-t0-a"));
            assertThrows(IllegalArgumentException.class,
                    () -> PromptExperiment.create(db, 1L, "v3", 0.0, 42, "PROMPT-v3-t0-a"));
            TuningParams seeded = PromptExperiment.create(db, 1L, "v3", null, 42, "PROMPT-v3-s42-a");
            assertEquals(42, seeded.seed());
            assertEquals(seeded, PromptExperiment.create(db, 1L, "v3", null, 42, "PROMPT-v3-s42-a"));
        }
    }

    @Test
    void agreementMatrixComparesEveryPair() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:prompt_agree;DB_CLOSE_DELAY=-1")) {
            long other = PromptExperiment.create(db, 1L, "v1", 0.0, "PROMPT-v1-t0-a").id();
            insertSeries(db, "QQQ", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"),
                    List.of(1L, other));
            try (Statement stmt = db.connection().createStatement()) {
                stmt.executeUpdate("UPDATE suggestions SET suggestedAction = 'AVOID' WHERE param_id = " + other
                        + " AND stock_id IN (SELECT id FROM stock WHERE \"date\" <= '2026-04-14')");
            }
            List<Long> ids = List.of(1L, other);
            Double[][] m = PromptExperiment.agreementMatrix(db, "QQQ",
                    LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"), ids);
            assertEquals(100.0, m[0][0], 1e-9);
            assertEquals(m[0][1], m[1][0]);
            assertEquals(100.0 * 12 / 22, m[0][1], 0.01);
            assertTrue(PromptExperiment.formatAgreement(ids, m)
                    .contains(String.format(java.util.Locale.US, "%.1f", m[0][1])));
        }
    }

    @Test
    void scoreReportsLabelMixForwardReturnsAgreementAndWalkForward() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:prompt_score;DB_CLOSE_DELAY=-1")) {
            Map<String, TuningParams> rows = PromptExperiment.setup(db, 1L);
            long control = rows.get("v1").id();
            insertSeries(db, "QQQ", LocalDate.parse("2026-03-02"), LocalDate.parse("2026-10-30"),
                    List.of(1L, control));

            List<PromptExperiment.Score> scores = PromptExperiment.score(db, "QQQ",
                    LocalDate.parse("2026-04-01"), LocalDate.parse("2026-07-02"), LocalDate.parse("2026-10-02"),
                    List.of(1L, control), control);

            PromptExperiment.Score base = scores.get(0);
            assertEquals(base.days(), base.labeledDays());
            assertEquals(base.labeledDays(),
                    base.mix().values().stream().mapToInt(Integer::intValue).sum());
            assertTrue(base.mix().get("BUY") > 0 && base.mix().get("SELL") > 0);
            assertNotNull(base.buyMinusHoldPct());
            assertEquals(100.0, base.agreementWithControlPct(), 1e-9);
            assertTrue(base.quarterly().ok());
            assertEquals(1, base.quarterly().periods().size());
            assertTrue(base.weekly().periods().size() > 10);
            assertEquals(null, scores.get(1).agreementWithControlPct());

            String text = PromptExperiment.format(scores, "QQQ", LocalDate.parse("2026-04-01"),
                    LocalDate.parse("2026-07-02"), LocalDate.parse("2026-10-02"));
            assertTrue(text.contains("BUY-HOLD"));
        }
    }

    /** Weekday closes on a sine wave, BUY near the trough and SELL near the peak, HOLD otherwise. */
    private static void insertSeries(Database db, String ticker, LocalDate from, LocalDate to, List<Long> paramIds)
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
            for (long paramId : paramIds) {
                try (PreparedStatement ps = db.connection().prepareStatement(
                        "INSERT INTO suggestions (stock_id, param_id, suggestedAction, confidence) VALUES (?, ?, ?, 0.7)")) {
                    ps.setLong(1, stockId);
                    ps.setLong(2, paramId);
                    ps.setString(3, action);
                    ps.executeUpdate();
                }
            }
            i++;
        }
    }
}
