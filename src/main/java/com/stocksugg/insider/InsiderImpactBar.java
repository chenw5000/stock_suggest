package com.stocksugg.insider;

import com.stocksugg.stock.StockRow;

import java.time.LocalDate;

/** One OHLCV bar in an {@link InsiderImpactRequest}. */
public record InsiderImpactBar(
        String date,
        double open,
        double high,
        double low,
        double close,
        long volume
) {
    public static InsiderImpactBar from(StockRow row) {
        return new InsiderImpactBar(
                row.date().toString(),
                row.open() == null ? 0.0 : row.open(),
                row.high() == null ? 0.0 : row.high(),
                row.low() == null ? 0.0 : row.low(),
                row.close() == null ? 0.0 : row.close(),
                row.volumeOrZero());
    }

    public LocalDate localDate() {
        return LocalDate.parse(date);
    }
}
