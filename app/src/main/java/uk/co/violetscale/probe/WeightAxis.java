package uk.co.violetscale.probe;

/** A home-chart weight scale with round ticks in the user's display units. */
final class WeightAxis {
    final double minKg, maxKg;
    final double[] ticksKg;

    private WeightAxis(double[] ticksKg) {
        this.ticksKg = ticksKg;
        minKg = ticksKg[0];
        maxKg = ticksKg[ticksKg.length - 1];
    }

    static WeightAxis forWindow(TrendSeries.Window data, String unit) {
        if (data.samples.isEmpty()) return null;
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (TrendSeries.Sample sample : data.samples) {
            low = Math.min(low, sample.value);
            high = Math.max(high, sample.value);
        }
        // One-day windows show raw dots only. A carried estimate becomes visible on day two.
        if (data.days.size() > 1) {
            for (TrendSeries.Day day : data.days) {
                low = Math.min(low, day.trend);
                high = Math.max(high, day.trend);
            }
        }
        return fit(low, high, unit);
    }

    static WeightAxis fit(double lowKg, double highKg, String unit) {
        if (!(lowKg >= 0 && highKg >= lowKg) || Double.isInfinite(highKg))
            throw new IllegalArgumentException("Invalid weight range");
        boolean pounds = "lb".equals(unit) || "st".equals(unit);
        double unitKg = pounds ? WeightUnits.toKg("lb", null, 1d) : 1d;
        double low = lowKg / unitKg, high = highKg / unitKg;
        double span = Math.max(pounds ? 2 : 1, (high - low) * 1.2);
        double start = Math.max(0, low - (span - (high - low)) * 0.5);
        double step = tickStep(span / 4);
        // Numerical noise on an exact tick must not add an entire extra interval.
        long first = (long)Math.floor(start / step + 1e-10);
        long last = (long)Math.ceil((start + span) / step - 1e-10);
        double[] ticks = new double[(int)(last - first + 1)];
        for (int i = 0; i < ticks.length; i++) ticks[i] = (first + i) * step * unitKg;
        return new WeightAxis(ticks);
    }

    private static double tickStep(double target) {
        double power = Math.pow(10, Math.floor(Math.log10(target)));
        double scaled = target / power;
        if (scaled < 1.5) return power;
        if (scaled < 2.25) return 2 * power;
        if (scaled < 3.75) return 2.5 * power;
        return (scaled < 7.5 ? 5 : 10) * power;
    }
}
