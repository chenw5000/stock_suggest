package com.stocksugg.stock;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.db.StockRepository.VolumeUpdate;
import com.stocksugg.yahoo.YahooFinanceClient;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Downloads Yahoo daily bars, computes indicators, and saves rows into the database.
 */
public final class StockDataImporter {

    /** Extra calendar days before the requested start so MA200 / CMO / CMF can warm up. */
    private static final int LOOKBACK_CALENDAR_DAYS = 400;

    /**
     * Extra calendar days before the earliest stored bar so {@code vol20} has a full 20-session
     * window even at the start of the series.
     */
    private static final int VOLUME_LOOKBACK_CALENDAR_DAYS = 45;

    private final YahooFinanceClient yahoo;
    private final StockRepository repository;

    public StockDataImporter(Database database) {
        this(new YahooFinanceClient(), new StockRepository(database));
    }

    public StockDataImporter(YahooFinanceClient yahoo, StockRepository repository) {
        this.yahoo = yahoo;
        this.repository = repository;
    }

    public int importDaily(String ticker, LocalDate fromInclusive) throws Exception {
        LocalDate lastComplete = MarketSession.lastCompleteSessionDate();
        if (fromInclusive.isAfter(lastComplete)) {
            System.out.println(ticker + ": nothing to import — last complete session is "
                    + lastComplete + " (before 1:00 PM PT, today is excluded)");
            return 0;
        }

        LocalDate fetchFrom = fromInclusive.minusDays(LOOKBACK_CALENDAR_DAYS);
        LocalDate fetchToExclusive = lastComplete.plusDays(1);

        List<StockBar> bars = yahoo.downloadDailyBars(ticker, fetchFrom, fetchToExclusive);
        List<StockRow> enriched = TechnicalIndicators.enrich(bars);
        List<StockRow> toSave = enriched.stream()
                .filter(row -> !row.date().isBefore(fromInclusive))
                .filter(row -> !row.date().isAfter(lastComplete))
                .toList();

        if (toSave.isEmpty()) {
            return 0;
        }

        LocalDate first = toSave.getFirst().date();
        LocalDate last = toSave.getLast().date();
        return repository.replaceRange(ticker.toUpperCase(), first, last, toSave);
    }

    /**
     * Downloads Yahoo volume for every stored trading day of {@code ticker}, computes the 20-day
     * average volume, and updates only {@code volume} / {@code vol20} on existing rows.
     *
     * @return number of stock rows updated
     */
    public int updateVolumeAndVol20(String ticker) throws Exception {
        String symbol = ticker.trim().toUpperCase(Locale.ROOT);
        List<StockRow> existing = repository.findAllBars(symbol);
        if (existing.isEmpty()) {
            System.out.println(symbol + ": no stored rows; skipped volume update.");
            return 0;
        }

        LocalDate first = existing.getFirst().date();
        LocalDate last = existing.getLast().date();
        LocalDate fetchFrom = first.minusDays(VOLUME_LOOKBACK_CALENDAR_DAYS);
        LocalDate fetchToExclusive = last.plusDays(1);

        System.out.println(symbol + ": downloading Yahoo volume "
                + fetchFrom + " .. " + last + " (" + existing.size() + " stored day(s)) …");
        List<StockBar> bars = yahoo.downloadDailyBars(symbol, fetchFrom, fetchToExclusive);
        List<StockRow> enriched = TechnicalIndicators.enrich(bars);

        Map<LocalDate, VolumeUpdate> byDate = new LinkedHashMap<>();
        for (StockRow row : enriched) {
            byDate.put(row.date(), new VolumeUpdate(row.volume(), row.vol20()));
        }

        Map<LocalDate, VolumeUpdate> updates = new LinkedHashMap<>();
        int missingOnYahoo = 0;
        int withVol20 = 0;
        for (StockRow row : existing) {
            VolumeUpdate values = byDate.get(row.date());
            if (values == null) {
                missingOnYahoo++;
                continue;
            }
            updates.put(row.date(), values);
            if (values.vol20() != null) {
                withVol20++;
            }
        }

        int updated = repository.updateVolumeAndVol20(symbol, updates);
        System.out.println(symbol + ": updated volume/vol20 on " + updated + " row(s); "
                + withVol20 + " with vol20, "
                + (updates.size() - withVol20) + " warm-up null vol20"
                + (missingOnYahoo == 0 ? "" : ", " + missingOnYahoo + " stored day(s) missing on Yahoo"));
        return updated;
    }
}
