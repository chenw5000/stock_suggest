package com.stocksugg.stock;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes moving averages, Chande Momentum (14), and Chaikin Money Flow (20).
 */
public final class TechnicalIndicators {

    public static final int CHANDE_PERIOD = 14;
    public static final int CHAIKIN_PERIOD = 20;

    private TechnicalIndicators() {}

    public static List<StockRow> enrich(List<StockBar> bars) {
        int n = bars.size();
        double[] close = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        long[] volume = new long[n];
        for (int i = 0; i < n; i++) {
            StockBar bar = bars.get(i);
            close[i] = bar.close();
            high[i] = bar.high();
            low[i] = bar.low();
            volume[i] = bar.volume();
        }

        Float[] ma5 = sma(close, 5);
        Float[] ma10 = sma(close, 10);
        Float[] ma20 = sma(close, 20);
        Float[] ma50 = sma(close, 50);
        Float[] ma200 = sma(close, 200);
        Float[] cmo = chandeMomentum(close, CHANDE_PERIOD);
        Float[] cmf = chaikinMoneyFlow(high, low, close, volume, CHAIKIN_PERIOD);

        List<StockRow> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StockBar bar = bars.get(i);
            rows.add(new StockRow(
                    bar.ticker(),
                    bar.date(),
                    (float) bar.open(),
                    (float) bar.high(),
                    (float) bar.low(),
                    (float) bar.close(),
                    ma5[i],
                    ma10[i],
                    ma20[i],
                    ma50[i],
                    ma200[i],
                    cmo[i],
                    cmf[i]));
        }
        return rows;
    }

    static Float[] sma(double[] values, int period) {
        Float[] out = new Float[values.length];
        if (values.length < period) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= period) {
                sum -= values[i - period];
            }
            if (i >= period - 1) {
                out[i] = (float) (sum / period);
            }
        }
        return out;
    }

    /** Chande Momentum Oscillator over {@code period} lookback. */
    static Float[] chandeMomentum(double[] close, int period) {
        Float[] out = new Float[close.length];
        for (int i = period; i < close.length; i++) {
            double up = 0;
            double down = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double change = close[j] - close[j - 1];
                if (change > 0) {
                    up += change;
                } else {
                    down += -change;
                }
            }
            double denom = up + down;
            out[i] = denom == 0 ? 0f : (float) (100.0 * (up - down) / denom);
        }
        return out;
    }

    /** Chaikin Money Flow over {@code period} lookback. */
    static Float[] chaikinMoneyFlow(
            double[] high, double[] low, double[] close, long[] volume, int period) {
        Float[] out = new Float[close.length];
        double[] mfv = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            double range = high[i] - low[i];
            double multiplier = range == 0 ? 0 : ((close[i] - low[i]) - (high[i] - close[i])) / range;
            mfv[i] = multiplier * volume[i];
        }
        for (int i = period - 1; i < close.length; i++) {
            double mfvSum = 0;
            double volSum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                mfvSum += mfv[j];
                volSum += volume[j];
            }
            out[i] = volSum == 0 ? 0f : (float) (mfvSum / volSum);
        }
        return out;
    }
}
