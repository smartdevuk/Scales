package uk.co.violetscale.probe;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Local display-axis regressions. Values and scale limits use kg, including in imperial mode. */
public final class HomeChartScaleFixtures {
    private static final double KG_PER_LB = 0.45359237;
    private static final double EPS = 1e-8;
    private static int checks;

    public static void main(String[] args) {
        WeightAxis flat = checkAxis(64.5, 64.5, "kg");
        check(flat.minKg < 64.5 && flat.maxKg > 64.5, "flat weight is inside the graph");
        check(flat.maxKg - flat.minKg < 3, "flat data stays locally scaled");
        WeightAxis narrow = checkAxis(64.50, 64.60, "kg");
        check(narrow.maxKg - narrow.minKg < 3, "a 100 g change does not expand the scale unnecessarily");
        checkAxis(51.975, 52.025, "kg");
        WeightAxis wide = checkAxis(58, 97, "kg");
        check(wide.maxKg - wide.minKg > narrow.maxKg - narrow.minKg, "range adapts to the data");
        WeightAxis floor = checkAxis(0, 0.4, "kg");
        near(floor.minKg, 0, "a low weight never produces a negative tick");
        checkAxis(0, 0, "lb");

        WeightAxis pounds = checkAxis(195.9 * KG_PER_LB, 196.1 * KG_PER_LB, "lb");
        WeightAxis stones = checkAxis(195.9 * KG_PER_LB, 196.1 * KG_PER_LB, "st");
        sameAxis(pounds, stones, "stones and pounds use a pound-spaced grid even over a stone boundary");
        checkAxis(117 * KG_PER_LB, 239 * KG_PER_LB, "lb");
        checkAxis(77.77, 77.77, "st");
        WeightAxis exact = WeightAxis.fit(126 * KG_PER_LB, 126 * KG_PER_LB, "lb");
        double almost = Math.nextAfter(126 * KG_PER_LB, Double.POSITIVE_INFINITY);
        sameAxis(exact, WeightAxis.fit(almost, almost, "lb"), "round-off at exact tick boundaries does not add another tick");
        for (double[] invalid : new double[][]{{-1, 2}, {2, 1}, {Double.NaN, 1}, {1, Double.POSITIVE_INFINITY}}) {
            boolean rejected = false;
            try { WeightAxis.fit(invalid[0], invalid[1], "kg"); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid extents rejected");
        }

        TrendSeries empty = new TrendSeries(Collections.<TrendSeries.Sample>emptyList());
        check(WeightAxis.forWindow(empty.window(TrendSeries.Range.MONTH, 30), "kg") == null, "empty chart");

        // A one-day chart paints only the dots. A preceding 100 kg reading still influences its
        // day's estimate, but that undrawn estimate must not shrink the visible range of the dots.
        TrendSeries series = new TrendSeries(Arrays.asList(point(0, 8, 100), point(1, 8, 60),
                point(1, 14, 62), point(2, 9, 61), point(3, 9, 250)));
        TrendSeries.Window oneDay = new TrendSeries.Window(series, 1, 1);
        double smoothed = oneDay.days.get(0).trend;
        check(smoothed > 62 && smoothed < 100, "fixture includes a carried estimate above all raw points");
        sameAxis(WeightAxis.fit(60, 62, "kg"), WeightAxis.forWindow(oneDay, "kg"), "one-day scale ignores the hidden trend");
        TrendSeries.Window twoDays = new TrendSeries.Window(series, 1, 2);
        sameAxis(WeightAxis.fit(60, smoothed, "kg"), WeightAxis.forWindow(twoDays, "kg"), "both raw readings and the drawn trend are included");
        check(WeightAxis.forWindow(new TrendSeries.Window(series, 4, 7), "lb") == null, "no data inside selected period");

        TrendSeries falling = new TrendSeries(Arrays.asList(point(0, 8, 50), point(1, 9, 86), point(1, 19, 83), point(2, 9, 84)));
        TrendSeries.Window finalDays = new TrendSeries.Window(falling, 1, 2);
        double lowTrend = finalDays.days.get(0).trend;
        check(lowTrend < 83, "fixture includes a visible trend beneath all raw points");
        sameAxis(WeightAxis.fit(lowTrend, 86, "st"), WeightAxis.forWindow(finalDays, "st"), "upper raw point remains visible in stone mode");
        System.out.println(checks + " home chart scale checks passed");
    }

    private static WeightAxis checkAxis(double low, double high, String unit) {
        WeightAxis axis = WeightAxis.fit(low, high, unit);
        double unitKg = "kg".equals(unit) ? 1 : KG_PER_LB;
        double padding = (high - low) * 0.1;
        String input = low + "–" + high + " kg in " + unit;
        check(axis != null && Double.isFinite(axis.minKg) && Double.isFinite(axis.maxKg), "finite bounds: " + input);
        check(axis.minKg >= -EPS && axis.maxKg > axis.minKg, "positive plotted span: " + input);
        check(axis.minKg <= Math.max(0, low - padding) + EPS && axis.maxKg >= high + padding - EPS,
                "endpoints have room and neither endpoint is clipped: " + input);
        double minSpan = "kg".equals(unit) ? 1 : 2 * KG_PER_LB;
        check(axis.maxKg - axis.minKg >= minSpan - EPS, "minimum vertical context: " + input);
        double[] ticks = axis.ticksKg;
        check(ticks.length >= 2, "labelled endpoints: " + input);
        near(ticks[0], axis.minKg, "first tick at minimum");
        near(ticks[ticks.length - 1], axis.maxKg, "last tick at maximum");
        double stepKg = ticks[1] - ticks[0];
        check(stepKg > 0 && Double.isFinite(stepKg), "finite positive tick spacing: " + input);
        for (int i = 2; i < ticks.length; i++) near(ticks[i] - ticks[i - 1], stepKg, "regular spacing: " + input);
        Set<String> labels = new HashSet<>();
        for (double tick : ticks) labels.add(WeightUnits.amount(tick, unit));
        check(labels.size() == ticks.length, "labels remain distinct after display rounding: " + input);
        double displayStep = stepKg / unitKg;
        double power = Math.pow(10, Math.floor(Math.log10(displayStep)));
        double normal = displayStep / power;
        check(close(normal, 1) || close(normal, 2) || close(normal, 2.5) || close(normal, 5) || close(normal, 10),
                "readable increment expressed in display units: " + input);
        near(ticks[0] / stepKg, Math.rint(ticks[0] / stepKg), "grid is aligned to whole intervals");
        return axis;
    }

    private static TrendSeries.Sample point(int day, int hour, double value) {
        return new TrendSeries.Sample("p" + day + "-" + hour, day * TrendSeries.MILLIS_PER_DAY + hour * 3_600_000L, 0, value);
    }

    private static void sameAxis(WeightAxis a, WeightAxis b, String message) {
        check(a != null && b != null && a.ticksKg.length == b.ticksKg.length, message);
        near(a.minKg, b.minKg, message);
        near(a.maxKg, b.maxKg, message);
        for (int i = 0; i < a.ticksKg.length; i++) near(a.ticksKg[i], b.ticksKg[i], message);
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) < EPS; }
    private static void near(double a, double b, String message) { check(close(a, b), message + " (" + a + " versus " + b + ")"); }
    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
        checks++;
    }
}
