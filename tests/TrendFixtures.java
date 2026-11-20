package uk.co.violetscale.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

/** Java-only regression checks for daily aggregation and elapsed-day smoothing. */
public final class TrendFixtures {
    private static int checks;

    public static void main(String[] args) {
        final long one = TrendSeries.MILLIS_PER_DAY;
        TrendSeries empty = new TrendSeries(Collections.<TrendSeries.Sample>emptyList());
        check(empty.days.isEmpty());
        check(empty.window(TrendSeries.Range.ALL, 10).change() == null);
        check(empty.window(TrendSeries.Range.WEEK, 10).meanOfDailyMedians() == null);

        // A day just before midnight UTC is already the next recorded local day.
        check(TrendSeries.localDay(one - 1_000, 60) == 1);
        check(TrendSeries.localDay(1_000, -60) == -1);
        List<TrendSeries.Sample> inputs = new ArrayList<>(Arrays.asList(
                sample(1, 12, 72), sample(1, 9, 68), sample(1, 13, 90),
                sample(8, 12, 76), sample(22, 11, 78)));
        inputs.add(sample(4, 10, Double.NaN));
        inputs.add(sample(5, 10, Double.POSITIVE_INFINITY));
        inputs.add(sample(2, 10, 0));
        TrendSeries s = new TrendSeries(inputs);
        check(s.samples.size() == 5);
        check(s.days.size() == 3);
        near(s.days.get(0).median, 72);
        near(s.days.get(0).trend, 72);
        near(s.days.get(1).trend, 74);
        near(s.days.get(2).trend, 77);
        check(s.samples.get(0).value == 68);
        check(s.samples.get(1).value == 72);

        TrendSeries.Window all = s.window(TrendSeries.Range.ALL, 22);
        near(all.change(), 5);
        near(all.meanOfDailyMedians(), (72.0 + 76 + 78) / 3);
        TrendSeries.Window week = s.window(TrendSeries.Range.WEEK, 8);
        check(week.from == 2 && week.to == 8 && week.days.size() == 1);
        near(week.days.get(0).trend, 74); // prior history is retained in the estimate
        check(week.change() == null);
        check(new TrendSeries.Window(s, 10, 20).samples.isEmpty());
        TrendSeries even = new TrendSeries(Arrays.asList(sample(1, 9, 60), sample(1, 11, 80)));
        near(even.days.get(0).median, 70);
        check(even.window(TrendSeries.Range.ALL, 1).change() == null);

        long leapFebruary = date(2024, 2, 29);
        check(TrendSeries.Range.MONTH.firstDay(leapFebruary, 0) == date(2024, 2, 1));
        check(TrendSeries.Range.QUARTER.firstDay(date(2026, 11, 20), 0) == date(2026, 8, 21));
        check(TrendSeries.Range.YEAR.firstDay(date(2025, 2, 28), 0) == date(2024, 3, 1));
        check(TrendSeries.Range.WEEK.firstDay(date(2026, 1, 1), 0) == date(2025, 12, 26));
        check(s.days.size() == 3); // no generated readings on unmeasured dates
        check(TrendGoal.read("track", "75", "70", "80") == null);
        check(TrendGoal.read("maintain", "", "80", "70") == null);
        check(TrendGoal.read("gain", "NaN", "", "") == null);
        check(TrendGoal.read("lose", "", "", "") == null);
        TrendGoal range = TrendGoal.read("maintain", "", "70", "75");
        near(range.distanceKg(70), 0);
        near(range.distanceKg(72), 0);
        near(range.distanceKg(75), 0);
        near(range.distanceKg(76), 1);
        near(range.distanceKg(69), -1);
        near(TrendGoal.read("gain", "75", "", "").distanceKg(73.5), -1.5);
        near(TrendGoal.read("lose", "74", "", "").distanceKg(75), 1);
        checkAnchoredChanges();
        System.out.println(checks + " trend fixture checks passed");
    }

    private static void checkAnchoredChanges() {
        List<TrendSeries.Sample> daily = new ArrayList<>();
        daily.add(sample(0, 8, 78));
        for (int day = 1; day <= 21; day++) daily.add(sample(day, 8, 70));
        TrendSeries falling = new TrendSeries(daily);
        near(falling.changeBefore(21, 7), -1); // Estimated weight moves from 72 to 71 kg.
        near(falling.changeBefore(21, 14), -3); // Estimated weight moves from 74 to 71 kg.
        // Inputs arrive out of order. Same-day repeats are aggregated once in both trend estimates.
        TrendSeries history = new TrendSeries(Arrays.asList(sample(15, 18, 80), sample(1, 22, 90),
                sample(8, 7, 76), sample(1, 8, 70), sample(15, 7, 76), sample(1, 12, 72), sample(90, 8, 140)));
        near(history.changeBefore(8, 7), 2); // Baseline trend 72, anchor trend 74, regardless of later days.
        near(history.changeBefore(15, 7), 2); // On day 15, the median is 78 and the trend is 76.
        near(history.changeBefore(15, 14), 4);
        check(history.changeBefore(14, 7) == null); // An interpolated anchor is forbidden.
        check(history.changeBefore(1, 7) == null);
        check(history.changeBefore(8, 14) == null);
        check(history.changeBefore(91, 7) == null);
        check(history.changeBefore(8, 0) == null);
        check(history.changeBefore(8, -1) == null);
        check(new TrendSeries(Collections.<TrendSeries.Sample>emptyList()).changeBefore(15, 7) == null);

        TrendSeries irregular = new TrendSeries(Arrays.asList(sample(12, 9, 78), sample(1, 14, 72),
                sample(19, 8, 76), sample(8, 20, 76), sample(20, 10, 200)));
        // Day 5 is four sevenths along the observed smoothed segment from day 1 (72) to day 8 (74).
        double day5 = 72 + 4.0 / 7 * 2;
        double day12 = 78 - 4 * Math.pow(0.5, 4.0 / 7);
        near(irregular.changeBefore(12, 7), day12 - day5);
        near(irregular.changeBefore(19, 14), (day12 + 76) / 2 - day5);
        check(irregular.days.size() == 5); // The interpolated comparison does not become an observation.
        TrendSeries sparse = new TrendSeries(Arrays.asList(sample(1, 8, 70), sample(9, 8, 74), sample(13, 8, 77), sample(23, 8, 79)));
        check(sparse.changeBefore(13, 7) == null); // Day 6 lies inside an eight-day gap.
        near(sparse.changeBefore(23, 14), sparse.days.get(3).trend - sparse.days.get(1).trend); // Exact day 9 is usable.
        TrendSeries flat = new TrendSeries(Arrays.asList(sample(1, 8, 70), sample(4, 8, 70), sample(10, 8, 70)));
        near(flat.changeBefore(10, 7), 0);

        // Saved London offsets put these records seven local days apart across 167- and 169-hour intervals.
        long springFirst = date(2026, 3, 29), autumnFirst = date(2026, 10, 25);
        long halfHour = 30 * 60_000L, week = 7 * TrendSeries.MILLIS_PER_DAY;
        long springTime = springFirst * TrendSeries.MILLIS_PER_DAY + halfHour;
        long autumnTime = autumnFirst * TrendSeries.MILLIS_PER_DAY - halfHour;
        TrendSeries.Sample spring = new TrendSeries.Sample("spring", springTime + week - 3_600_000L, 60, 72);
        TrendSeries.Sample autumn = new TrendSeries.Sample("autumn", autumnTime + week + 3_600_000L, 0, 72);
        check(spring.day == springFirst + 7);
        check(autumn.day == autumnFirst + 7);
        near(new TrendSeries(Arrays.asList(spring, new TrendSeries.Sample("before", springTime, 0, 70))).changeBefore(spring.day, 7), 1);
        near(new TrendSeries(Arrays.asList(autumn, new TrendSeries.Sample("before", autumnTime, 60, 70))).changeBefore(autumn.day, 7), 1);
    }

    private static long date(int year, int month, int day) {
        GregorianCalendar c = new GregorianCalendar(TrendSeries.UTC, Locale.UK);
        c.clear();
        c.set(year, month - 1, day);
        return TrendSeries.localDay(c.getTimeInMillis(), 0);
    }

    private static TrendSeries.Sample sample(long day, int hour, double value) {
        return new TrendSeries.Sample("day-" + day + "-" + hour, day * TrendSeries.MILLIS_PER_DAY + hour * 3_600_000L, 0, value);
    }

    private static void check(boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("Trend check " + checks);
    }

    private static void near(double actual, double expected) { check(Math.abs(actual - expected) < 1e-9); }
}
