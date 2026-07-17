package com.stocksugg.stock;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.yahoo.YahooFinanceClient;

import java.time.LocalDate;
import java.util.List;

/**
 * Downloads Yahoo daily bars, computes indicators, and saves rows into the database.
 */
public final class StockDataImporter {

    /** Extra calendar days before the requested start so MA200 / CMO / CMF can warm up. */
    private static final int LOOKBACK_CALENDAR_DAYS = 400;

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
}
