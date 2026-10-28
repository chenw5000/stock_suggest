package com.stocksugg.insider;

import com.stocksugg.db.Database;
import com.stocksugg.db.StockRepository;
import com.stocksugg.stock.StockBar;
import com.stocksugg.stock.StockRow;
import com.stocksugg.yahoo.YahooFinanceClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads OHLCV bars from StockSugg's database and asks insiderTrans for an impact score.
 * Existing rows may lack {@code volume}; when missing, volumes are filled from Yahoo for
 * the same dates so the impact API still receives a full OHLCV package.
 */
public final class InsiderImpactService {

    /** Bars ending on {@code asOf} (inclusive) required by the impact API. */
    public static final int REQUIRED_BARS = 120;

    private final StockRepository stocks;
    private final InsiderImpactClient client;
    private final YahooFinanceClient yahoo;

    public InsiderImpactService(Database database) {
        this(new StockRepository(database), new InsiderImpactClient(), new YahooFinanceClient());
    }

    public InsiderImpactService(
            StockRepository stocks, InsiderImpactClient client, YahooFinanceClient yahoo) {
        this.stocks = stocks;
        this.client = client;
        this.yahoo = yahoo;
    }

    /**
     * Fetches {@value #REQUIRED_BARS} daily bars ending on {@code asOf} and posts them to
     * insiderTrans {@code /api/impact}.
     */
    public InsiderImpactResult evaluate(String ticker, LocalDate asOf) throws Exception {
        return evaluate(ticker, asOf, REQUIRED_BARS);
    }

    public InsiderImpactResult evaluate(String ticker, LocalDate asOf, int barCount) throws Exception {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        if (asOf == null) {
            throw new IllegalArgumentException("asOf is required");
        }
        if (barCount < 1) {
            throw new IllegalArgumentException("barCount must be >= 1");
        }

        String symbol = ticker.trim().toUpperCase(Locale.ROOT);
        List<StockRow> bars = stocks.findBarsEndingOn(symbol, asOf, barCount);
        if (bars.size() < barCount) {
            throw new IllegalStateException(symbol + " has only " + bars.size()
                    + " bar(s) on or before " + asOf + "; need " + barCount
                    + ". Import more Yahoo history first.");
        }
        List<StockRow> withVolume = fillMissingVolumes(symbol, bars);
        InsiderImpactRequest request = InsiderImpactRequest.of(symbol, asOf, withVolume);
        return client.evaluate(request);
    }

    /**
     * Builds the impact request body without calling the remote API (useful for tests / dry runs).
     */
    public InsiderImpactRequest buildRequest(String ticker, LocalDate asOf) throws Exception {
        return buildRequest(ticker, asOf, REQUIRED_BARS);
    }

    public InsiderImpactRequest buildRequest(String ticker, LocalDate asOf, int barCount)
            throws Exception {
        String symbol = ticker.trim().toUpperCase(Locale.ROOT);
        List<StockRow> bars = stocks.findBarsEndingOn(symbol, asOf, barCount);
        if (bars.size() < barCount) {
            throw new IllegalStateException(symbol + " has only " + bars.size()
                    + " bar(s) on or before " + asOf + "; need " + barCount);
        }
        return InsiderImpactRequest.of(symbol, asOf, fillMissingVolumes(symbol, bars));
    }

    /**
     * When any DB bar lacks volume, downloads that date window from Yahoo and copies volumes
     * by date onto the StockSugg rows (OHLC stay from the database).
     */
    List<StockRow> fillMissingVolumes(String ticker, List<StockRow> bars) throws Exception {
        boolean missing = bars.stream().anyMatch(row -> row.volume() == null);
        if (!missing) {
            return bars;
        }
        LocalDate from = bars.getFirst().date();
        LocalDate toExclusive = bars.getLast().date().plusDays(1);
        List<StockBar> yahooBars = yahoo.downloadDailyBars(ticker, from, toExclusive);
        Map<LocalDate, Long> volumeByDate = new HashMap<>();
        for (StockBar bar : yahooBars) {
            volumeByDate.put(bar.date(), bar.volume());
        }
        List<StockRow> out = new ArrayList<>(bars.size());
        int filled = 0;
        for (StockRow row : bars) {
            if (row.volume() != null) {
                out.add(row);
                continue;
            }
            Long volume = volumeByDate.get(row.date());
            out.add(new StockRow(
                    row.ticker(), row.date(),
                    row.open(), row.high(), row.low(), row.close(),
                    row.ma5(), row.ma10(), row.ma20(), row.ma50(), row.ma200(),
                    row.rsi14(), row.chandeMmt(), row.chalkinMF(),
                    volume == null ? 0L : volume));
            filled++;
        }
        System.out.println(ticker + ": filled volume for " + filled + " of " + bars.size()
                + " bar(s) from Yahoo (not yet stored in DB).");
        return out;
    }
}
