package com.stocksugg.stock;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes moving averages, Wilder RSI (14), Chande Momentum (14), and Chaikin Money Flow (20).
 */
public final class TechnicalIndicators {

    public static final int RSI_PERIOD = 14;
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
        Float[] rsi14 = relativeStrengthIndex(close, RSI_PERIOD);
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
                    rsi14[i],
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

    /** Wilder's Relative Strength Index over {@code period} price changes. */
    public static Float[] relativeStrengthIndex(double[] close, int period) {
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1");
        }
        Float[] out = new Float[close.length];
        if (close.length <= period) {
            return out;
        }

        double gainSum = 0;
        double lossSum = 0;
        for (int i = 1; i <= period; i++) {
            double change = close[i] - close[i - 1];
            gainSum += Math.max(change, 0);
            lossSum += Math.max(-change, 0);
        }

        double averageGain = gainSum / period;
        double averageLoss = lossSum / period;
        out[period] = rsiValue(averageGain, averageLoss);

        for (int i = period + 1; i < close.length; i++) {
            double change = close[i] - close[i - 1];
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            averageGain = ((averageGain * (period - 1)) + gain) / period;
            averageLoss = ((averageLoss * (period - 1)) + loss) / period;
            out[i] = rsiValue(averageGain, averageLoss);
        }
        return out;
    }

    private static float rsiValue(double averageGain, double averageLoss) {
        if (averageLoss == 0) {
            return averageGain == 0 ? 50f : 100f;
        }
        double relativeStrength = averageGain / averageLoss;
        return (float) (100.0 - (100.0 / (1.0 + relativeStrength)));
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
