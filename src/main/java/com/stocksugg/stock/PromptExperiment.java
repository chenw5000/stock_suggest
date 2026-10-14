package com.stocksugg.stock;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.db.StrategyOptimizeRepository;
import com.stocksugg.db.TuningParamsRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Prompt-version experiment: one {@code tuning_params} row per {@link GeminiPrompts} version
 * (same lookback and weights as a base param), historical suggestions for one ticker per row, and
 * a scorecard comparing the label quality and walk-forward results of each row.
 */
public final class PromptExperiment {

    public static final String NAME_PREFIX = "PROMPT-";
    public static final int FORWARD_DAYS = 10;
    private static final int CHUNK_SIZE = GeminiStockAdvisor.DEFAULT_HISTORICAL_CHUNK_SIZE;
    private static final int MAX_ATTEMPTS = 4;
    private static final long PAUSE_BETWEEN_CHUNKS_MILLIS = 4_000;
    private static final long RETRY_BASE_MILLIS = 30_000;
    private static final List<String> LABELS =
            List.of("BUY", "HOLD", "SELL", "AVOID_HIGH", "AVOID_LOW", "OTHER");

    public record LabelStats(String label, int days, Double avgForwardPct, Double excessPct) {}

    public record Score(
            long paramId,
            String name,
            String promptVersion,
            Double temperature,
            Integer seed,
            int days,
            int labeledDays,
            Map<String, Integer> mix,
            List<LabelStats> forward,
            Double buyMinusHoldPct,
            Double sellAvoidMinusHoldPct,
            Double agreementWithControlPct,
            WalkForward.TickerResult quarterly,
            WalkForward.TickerResult weekly) {}

    private PromptExperiment() {}

    /** One row per prompt version named {@code PROMPT-<version>}; existing rows are reused. */
    public static Map<String, TuningParams> setup(Database db, long baseParamId) throws SQLException {
        Map<String, TuningParams> out = new LinkedHashMap<>();
        for (GeminiPrompts.Prompt prompt : GeminiPrompts.all()) {
            out.put(prompt.version(),
                    create(db, baseParamId, prompt.version(), null, NAME_PREFIX + prompt.version()));
        }
        return out;
    }

    public static TuningParams create(
            Database db, long baseParamId, String version, Double temperature, String name) throws SQLException {
        return create(db, baseParamId, version, temperature, null, name);
    }

    /**
     * Row {@code name} using prompt {@code version}, {@code temperature} and {@code seed} (null =
     * model default), with lookback and weights copied from {@code baseParamId}. An existing row
     * with that name is reused, but only if its version, temperature and seed match.
     */
    public static TuningParams create(
            Database db, long baseParamId, String version, Double temperature, Integer seed, String name)
            throws SQLException {
        GeminiPrompts.Prompt prompt = GeminiPrompts.forVersion(version);
        TuningParamsRepository repo = new TuningParamsRepository(db);
        TuningParams existing = repo.findByName(name).orElse(null);
        if (existing != null) {
            if (!existing.promptVersionOrDefault().equals(prompt.version())
                    || !java.util.Objects.equals(existing.temperature(), temperature)
                    || !java.util.Objects.equals(existing.seed(), seed)) {
                throw new IllegalArgumentException("tuning_params " + name + " (id=" + existing.id()
                        + ") already exists with prompt " + existing.promptVersionOrDefault()
                        + ", temperature " + existing.temperature() + " and seed " + existing.seed());
            }
            return existing;
        }
        TuningParams base = repo.requireById(baseParamId);
        return repo.insert(new TuningParams(
                0,
                base.numDatePoint(),
                base.ma5Weight(),
                base.ma10Weight(),
                base.ma20Weight(),
                base.ma50Weight(),
                base.ma200Weight(),
                base.rsiWeight(),
                base.cmoWeight(),
                base.cmfWeight(),
                name,
                "Prompt experiment " + prompt.version() + ": " + prompt.label()
                        + (temperature == null ? "" : ", temperature " + temperature)
                        + (seed == null ? "" : ", seed " + seed)
                        + " (lookback and weights copied from param " + baseParamId + ")",
                prompt.version(),
                temperature,
                seed));
    }

    /**
     * Asks Gemini for every trading day in [{@code from}, {@code to}] that has no suggestion for
     * {@code paramId} yet; failed chunks are retried with backoff. Returns the days still missing.
     */
    public static int backfillMissing(
            Database db,
            GeminiStockAdvisor advisor,
            String ticker,
            LocalDate from,
            LocalDate to,
            long paramId) throws Exception {
        StockRepository stocks = new StockRepository(db);
        List<LocalDate> missing = missingDates(stocks, ticker, from, to, paramId);
        System.out.println("Prompt backfill " + ticker + " param " + paramId + ": "
                + missing.size() + " day(s) without a suggestion in " + from + ".." + to);
        for (int i = 0; i < missing.size(); i += CHUNK_SIZE) {
            List<LocalDate> chunk = missing.subList(i, Math.min(i + CHUNK_SIZE, missing.size()));
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    advisor.adviseAsOfMany(ticker, chunk, paramId);
                    break;
                } catch (Exception e) {
                    System.err.println("Chunk " + chunk.getFirst() + ".." + chunk.getLast()
                            + " attempt " + attempt + " failed: " + e.getMessage());
                    if (attempt == MAX_ATTEMPTS) {
                        break;
                    }
                    Thread.sleep(RETRY_BASE_MILLIS * attempt);
                }
            }
            System.out.println("Prompt backfill param " + paramId + ": "
                    + Math.min(i + CHUNK_SIZE, missing.size()) + " of " + missing.size() + " day(s) requested");
            Thread.sleep(PAUSE_BETWEEN_CHUNKS_MILLIS);
        }
        int left = missingDates(stocks, ticker, from, to, paramId).size();
        System.out.println("Prompt backfill param " + paramId + " done; " + left + " day(s) still missing.");
        return left;
    }

    private static List<LocalDate> missingDates(
            StockRepository stocks, String ticker, LocalDate from, LocalDate to, long paramId)
            throws SQLException {
        return stocks.findBacktestDays(ticker, from, to, paramId).stream()
                .filter(d -> d.suggestedAction() == null || d.suggestedAction().isBlank())
                .map(BacktestDay::date)
                .toList();
    }

    /**
     * Scores each param on {@code ticker}: label mix and average {@value #FORWARD_DAYS}-day forward
     * return per label over [{@code from}, {@code to}], agreement with {@code controlParamId}, and
     * walk-forward results (quarterly and weekly re-optimization) over [{@code testFrom}, {@code to}].
     */
    public static List<Score> score(
            Database db,
            String ticker,
            LocalDate from,
            LocalDate testFrom,
            LocalDate to,
            List<Long> paramIds,
            Long controlParamId) throws SQLException {
        StockRepository stocks = new StockRepository(db);
        StrategyOptimizeRepository optimize = new StrategyOptimizeRepository(db);
        TuningParamsRepository params = new TuningParamsRepository(db);
        Map<LocalDate, String> control = controlParamId == null
                ? Map.of()
                : actionsByDate(stocks.findBacktestDays(ticker, from, to, controlParamId));

        List<Score> scores = new ArrayList<>();
        for (long paramId : paramIds) {
            TuningParams row = params.requireById(paramId);
            List<BacktestDay> withFuture = stocks.findBacktestDays(ticker, from, to.plusDays(31), paramId);
            List<BacktestDay> days = withFuture.stream().filter(d -> !d.date().isAfter(to)).toList();

            Map<String, Integer> mix = new LinkedHashMap<>();
            Map<String, double[]> sums = new HashMap<>();
            LABELS.forEach(l -> {
                mix.put(l, 0);
                sums.put(l, new double[2]);
            });
            double allSum = 0;
            int allCount = 0;
            int labeled = 0;
            for (int i = 0; i < days.size(); i++) {
                BacktestDay day = days.get(i);
                String label = labelOf(day);
                if (label == null) {
                    continue;
                }
                labeled++;
                mix.merge(label, 1, Integer::sum);
                if (i + FORWARD_DAYS < withFuture.size() && day.close() > 0) {
                    double fwd = (withFuture.get(i + FORWARD_DAYS).close() / day.close() - 1.0) * 100.0;
                    sums.get(label)[0] += fwd;
                    sums.get(label)[1]++;
                    allSum += fwd;
                    allCount++;
                }
            }
            double baseline = allCount == 0 ? 0 : allSum / allCount;
            List<LabelStats> forward = new ArrayList<>();
            Map<String, Double> avg = new HashMap<>();
            for (String label : LABELS) {
                double[] s = sums.get(label);
                Double mean = s[1] == 0 ? null : s[0] / s[1];
                avg.put(label, mean);
                forward.add(new LabelStats(label, (int) s[1], round2(mean),
                        mean == null ? null : round2(mean - baseline)));
            }
            Double buyMinusHold = avg.get("BUY") == null || avg.get("HOLD") == null
                    ? null : round2(avg.get("BUY") - avg.get("HOLD"));
            double[] exits = {
                    sums.get("SELL")[0] + sums.get("AVOID_HIGH")[0] + sums.get("AVOID_LOW")[0],
                    sums.get("SELL")[1] + sums.get("AVOID_HIGH")[1] + sums.get("AVOID_LOW")[1]};
            Double exitMinusHold = exits[1] == 0 || avg.get("HOLD") == null
                    ? null : round2(exits[0] / exits[1] - avg.get("HOLD"));

            Double agreement = null;
            if (controlParamId != null && controlParamId != paramId) {
                int both = 0;
                int same = 0;
                for (BacktestDay day : days) {
                    String mine = normalized(day.suggestedAction());
                    String theirs = control.get(day.date());
                    if (mine != null && theirs != null) {
                        both++;
                        if (mine.equals(theirs)) {
                            same++;
                        }
                    }
                }
                agreement = both == 0 ? null : round2(same * 100.0 / both);
            }

            WalkForward.TickerResult quarterly = WalkForward.runTicker(stocks, optimize, ticker,
                    WalkForward.periods(WalkForward.Cadence.QUARTERLY, testFrom, to), testFrom, to, paramId, false);
            WalkForward.TickerResult weekly = WalkForward.runTicker(stocks, optimize, ticker,
                    WalkForward.periods(WalkForward.Cadence.WEEKLY, testFrom, to), testFrom, to, paramId, false);

            scores.add(new Score(paramId, row.name(), row.promptVersionOrDefault(), row.temperature(),
                    row.seed(), days.size(), labeled,
                    mix, forward, buyMinusHold, exitMinusHold, agreement, quarterly, weekly));
        }
        return scores;
    }

    /**
     * Percent of days in [{@code from}, {@code to}] where params i and j gave {@code ticker} the same
     * action, over days both labeled; null when they share no labeled day.
     */
    public static Double[][] agreementMatrix(
            Database db, String ticker, LocalDate from, LocalDate to, List<Long> paramIds) throws SQLException {
        StockRepository stocks = new StockRepository(db);
        List<Map<LocalDate, String>> actions = new ArrayList<>();
        for (long paramId : paramIds) {
            actions.add(actionsByDate(stocks.findBacktestDays(ticker, from, to, paramId)));
        }
        Double[][] out = new Double[paramIds.size()][paramIds.size()];
        for (int i = 0; i < actions.size(); i++) {
            for (int j = 0; j < actions.size(); j++) {
                int both = 0;
                int same = 0;
                for (Map.Entry<LocalDate, String> e : actions.get(i).entrySet()) {
                    String other = actions.get(j).get(e.getKey());
                    if (other != null) {
                        both++;
                        if (other.equals(e.getValue())) {
                            same++;
                        }
                    }
                }
                out[i][j] = both == 0 ? null : round2(same * 100.0 / both);
            }
        }
        return out;
    }

    /** Plain-text agreement matrix from {@link #agreementMatrix}. */
    public static String formatAgreement(List<Long> paramIds, Double[][] matrix) {
        StringBuilder out = new StringBuilder(String.format(Locale.US, "%nSame action on the same day, %% of days%n%-6s", "param"));
        paramIds.forEach(id -> out.append(String.format(Locale.US, " %6d", id)));
        out.append(System.lineSeparator());
        for (int i = 0; i < paramIds.size(); i++) {
            out.append(String.format(Locale.US, "%-6d", paramIds.get(i)));
            for (int j = 0; j < paramIds.size(); j++) {
                out.append(String.format(Locale.US, " %6s",
                        i == j ? "" : matrix[i][j] == null ? "-" : String.format(Locale.US, "%.1f", matrix[i][j])));
            }
            out.append(System.lineSeparator());
        }
        return out.toString();
    }

    /** Plain-text scorecard. */
    public static String format(List<Score> scores, String ticker, LocalDate from, LocalDate testFrom, LocalDate to) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.US,
                "Prompt experiment %s: labels %s..%s, walk-forward test %s..%s (training = previous 3 months)%n",
                ticker, from, to, testFrom, to));
        out.append(String.format(Locale.US, "%n%-6s %-18s %-4s %5s %5s %9s  %s%n",
                "param", "name", "ver", "temp", "seed", "labeled", "label mix"));
        for (Score s : scores) {
            StringBuilder mix = new StringBuilder();
            s.mix().forEach((k, v) -> {
                if (v > 0) {
                    mix.append(k).append(' ').append(v).append("  ");
                }
            });
            out.append(String.format(Locale.US, "%-6d %-18s %-4s %5s %5s %4d/%-4d  %s%n",
                    s.paramId(), s.name(), s.promptVersion(),
                    s.temperature() == null ? "-" : String.format(Locale.US, "%.1f", s.temperature()),
                    s.seed() == null ? "-" : String.valueOf(s.seed()),
                    s.labeledDays(), s.days(), mix.toString().trim()));
        }

        out.append(String.format(Locale.US, "%n%d-day forward return by label (excess vs all labeled days)%n", FORWARD_DAYS));
        out.append(String.format(Locale.US, "%-6s %-4s", "param", "ver"));
        for (String label : LABELS) {
            out.append(String.format(Locale.US, " %18s", label));
        }
        out.append(String.format(Locale.US, " %10s %12s %9s%n", "BUY-HOLD", "exits-HOLD", "agree%"));
        for (Score s : scores) {
            out.append(String.format(Locale.US, "%-6d %-4s", s.paramId(), s.promptVersion()));
            for (LabelStats l : s.forward()) {
                out.append(String.format(Locale.US, " %18s", l.days() == 0 ? "-"
                        : String.format(Locale.US, "%+.2f%% (%+.2f) n=%d", l.avgForwardPct(), l.excessPct(), l.days())));
            }
            out.append(String.format(Locale.US, " %10s %12s %9s%n",
                    pct(s.buyMinusHoldPct()), pct(s.sellAvoidMinusHoldPct()),
                    s.agreementWithControlPct() == null ? "-" : String.format(Locale.US, "%.1f", s.agreementWithControlPct())));
        }

        out.append(String.format(Locale.US, "%nWalk-forward on the test span, $10,000 start%n"));
        out.append(String.format(Locale.US, "%-6s %-4s %10s %10s %10s %10s %8s %8s %7s %10s%n",
                "param", "ver", "B&H", "WF qtr", "WF week", "fixed", "DD qtr", "DD B&H", "inMkt", "trades q/w"));
        for (Score s : scores) {
            WalkForward.TickerResult q = s.quarterly();
            WalkForward.TickerResult w = s.weekly();
            if (!q.ok()) {
                out.append(String.format(Locale.US, "%-6d %-4s skipped: %s%n", s.paramId(), s.promptVersion(), q.skipReason()));
                continue;
            }
            out.append(String.format(Locale.US, "%-6d %-4s %10s %10s %10s %10s %7.1f%% %7.1f%% %6.1f%% %5d/%-4s%n",
                    s.paramId(), s.promptVersion(),
                    pct(q.buyHold().returnPct()), pct(q.walkForward().returnPct()),
                    w.ok() ? pct(w.walkForward().returnPct()) : "-",
                    pct(q.fixed().returnPct()),
                    q.walkForward().maxDrawdownPct(), q.buyHold().maxDrawdownPct(),
                    q.walkForward().timeInMarketPct(),
                    q.walkForward().trades(), w.ok() ? String.valueOf(w.walkForward().trades()) : "-"));
        }
        return out.toString();
    }

    private static Map<LocalDate, String> actionsByDate(List<BacktestDay> days) {
        Map<LocalDate, String> out = new HashMap<>();
        for (BacktestDay day : days) {
            String action = normalized(day.suggestedAction());
            if (action != null) {
                out.put(day.date(), action);
            }
        }
        return out;
    }

    private static String normalized(String action) {
        return action == null || action.isBlank() ? null : action.trim().toUpperCase(Locale.ROOT);
    }

    /** BUY / HOLD / SELL / AVOID_HIGH / AVOID_LOW, OTHER for anything else; null when unlabeled. */
    private static String labelOf(BacktestDay day) {
        String action = normalized(day.suggestedAction());
        if (action == null) {
            return null;
        }
        String key = day.actionKey();
        key = key == null ? action : key.trim().toUpperCase(Locale.ROOT);
        return LABELS.contains(key) ? key : "OTHER";
    }

    private static String pct(Double value) {
        return value == null ? "-" : String.format(Locale.US, "%+.2f%%", value);
    }

    private static Double round2(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }
}
