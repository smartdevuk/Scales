package uk.co.violetscale.probe;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** The x axis is local calendar time, using the UTC offset saved with each measurement. */
final class TrendSeries {
    static final long MILLIS_PER_DAY = 86_400_000L;
    static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    static final double HALF_LIFE_DAYS = 7;

    enum Range {
        WEEK("1W"), MONTH("1M"), QUARTER("3M"), YEAR("1Y"), ALL("All");
        final String label;
        Range(String label) { this.label = label; }

        long firstDay(long today, long firstRecord) {
            if (this == ALL) return firstRecord;
            if (this == WEEK) return today - 6;
            Calendar start = new GregorianCalendar(UTC, Locale.UK);
            start.setTimeInMillis(today * MILLIS_PER_DAY);
            // End is inclusive. For example, a month ending 20 Nov begins 21 Oct.
            start.add(Calendar.DAY_OF_MONTH, 1);
            start.add(Calendar.MONTH, this == MONTH ? -1 : this == QUARTER ? -3 : -12);
            return localDay(start.getTimeInMillis(), 0);
        }
    }

    static final class Sample {
        final String entryId;
        final long timeMs, day;
        final int offsetMinutes;
        final double x, value;

        Sample(String entryId, long timeMs, int offsetMinutes, double value) {
            this.entryId = entryId;
            this.timeMs = timeMs;
            this.offsetMinutes = offsetMinutes;
            this.x = localMillis(timeMs, offsetMinutes) / (double) MILLIS_PER_DAY;
            this.day = localDay(timeMs, offsetMinutes);
            this.value = value;
        }
    }

    static final class Day {
        final long day;
        final double x, median, trend;
        Day(long day, double x, double median, double trend) {
            this.day = day;
            this.x = x;
            this.median = median;
            this.trend = trend;
        }
    }

    final List<Sample> samples;
    final List<Day> days;

    TrendSeries(List<Sample> input) {
        List<Sample> sorted = new ArrayList<>();
        for (Sample sample : input) {
            if (sample != null && sample.value > 0 && !Double.isInfinite(sample.value)) sorted.add(sample);
        }
        Collections.sort(sorted, new Comparator<Sample>() {
            @Override public int compare(Sample a, Sample b) { return Double.compare(a.x, b.x); }
        });
        samples = Collections.unmodifiableList(sorted);
        List<Day> estimates = new ArrayList<>();
        int from = 0;
        while (from < sorted.size()) {
            long day = sorted.get(from).day;
            List<Double> values = new ArrayList<>();
            double xSum = 0;
            int end = from;
            while (end < sorted.size() && sorted.get(end).day == day) {
                Sample sample = sorted.get(end++);
                values.add(sample.value);
                xSum += sample.x;
            }
            Collections.sort(values);
            int mid = values.size() / 2;
            double median = (values.get((values.size() - 1) / 2) + values.get(mid)) / 2;
            Day previous = estimates.isEmpty() ? null : estimates.get(estimates.size() - 1);
            double trend = median;
            if (previous != null) {
                long elapsedDays = day - previous.day;
                double oldWeight = Math.pow(0.5, elapsedDays / HALF_LIFE_DAYS);
                trend = oldWeight * previous.trend + (1 - oldWeight) * median;
            }
            estimates.add(new Day(day, xSum / values.size(), median, trend));
            from = end;
        }
        days = Collections.unmodifiableList(estimates);
    }

    static long localDay(long timeMs, int offsetMinutes) {
        return (long) Math.floor(localMillis(timeMs, offsetMinutes) / (double) MILLIS_PER_DAY);
    }

    private static long localMillis(long utcMs, int offsetMinutes) { return utcMs + offsetMinutes * 60_000L; }

    Window window(Range range, long today) {
        long first = days.isEmpty() ? today : Math.min(today, days.get(0).day);
        return new Window(this, range.firstDay(today, first), today);
    }

    /** Compare the smoothed weight on a recorded local day with the trend N calendar days earlier. */
    Double changeBefore(long anchorDay, int daysBack) {
        if (daysBack <= 0) return null;
        int anchorIndex = firstOnOrAfter(anchorDay);
        if (anchorIndex == days.size() || days.get(anchorIndex).day != anchorDay) return null;
        Day anchor = days.get(anchorIndex);
        long target = anchorDay - daysBack;
        int rightIndex = firstOnOrAfter(target);
        // target precedes an observed anchor, so the right-hand day exists and is at most the anchor.
        Day right = days.get(rightIndex);
        if (right.day == target) return anchor.trend - right.trend;
        if (rightIndex == 0) return null;
        Day left = days.get(rightIndex - 1);
        long gap = right.day - left.day;
        if (gap > 7) return null;
        double fraction = (target - left.day) / (double) gap;
        return anchor.trend - (left.trend + fraction * (right.trend - left.trend));
    }

    private int firstOnOrAfter(long wanted) {
        int lo = 0, hi = days.size();
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (days.get(mid).day < wanted) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    static final class Window {
        final long from, to;
        final List<Sample> samples = new ArrayList<>();
        final List<Day> days = new ArrayList<>();

        Window(TrendSeries series, long from, long to) {
            this.from = from;
            this.to = to;
            for (Sample s : series.samples) if (s.day >= from && s.day <= to) samples.add(s);
            for (Day d : series.days) if (d.day >= from && d.day <= to) days.add(d);
        }

        Double change() { return days.size() < 2 ? null : days.get(days.size() - 1).trend - days.get(0).trend; }

        Double meanOfDailyMedians() {
            if (days.isEmpty()) return null;
            double total = 0;
            for (Day day : days) total += day.median;
            return total / days.size();
        }
    }
}
