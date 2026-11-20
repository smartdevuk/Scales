package uk.co.violetscale.probe;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class TrendsScreen {
    private static final int PAPER = 0xFFF6F2FF, MUTED = 0xFFC0B8CB, ACCENT = 0xFFCEA9FF;
    private final HomeActivity host;
    private final LinearLayout page, readings;
    private final TrendSeries weight, waist;
    private final long today;
    private boolean showWaist;
    private TrendSeries.Range range = TrendSeries.Range.MONTH;

    private TrendsScreen(HomeActivity host) {
        this.host = host;
        List<Store.Entry> entries = host.store.all();
        weight = series(entries, false);
        waist = series(entries, true);
        showWaist = weight.samples.isEmpty() && !waist.samples.isEmpty();
        today = today();
        page = host.ui.column();
        readings = host.ui.column();
        host.content.addView(page);
    }

    static void show(HomeActivity host) { new TrendsScreen(host).render(); }

    static TrendPlot addSparkline(HomeActivity host, LinearLayout parent, TrendSeries history) {
        TrendSeries.Window w = history.window(TrendSeries.Range.MONTH, today());
        LinearLayout panel = host.ui.card();
        host.ui.pad(panel, 14, 12, 14, 12);
        LinearLayout heading = host.ui.row();
        heading.addView(host.ui.heading("Weight history", 17), Ui.flex());
        heading.addView(host.ui.text("Past month", 13, MUTED));
        panel.addView(heading);
        parent.addView(panel);
        if (w.samples.isEmpty()) {
            host.ui.gap(panel, 7);
            panel.addView(host.ui.text(history.samples.isEmpty() ? "Weigh in to start your chart."
                    : "No weight readings in the past month.", 14, MUTED));
            return null;
        }
        int count = w.samples.size();
        boolean estimated = w.days.size() > 1;
        String detail = count + (count == 1 ? " reading" : " readings");
        detail += estimated ? " · Estimated trend " + host.weight(w.days.get(w.days.size() - 1).trend)
                : " · A second day starts the trend";
        host.ui.gap(panel, 3);
        panel.addView(host.ui.text(detail, 13, MUTED));
        TrendPlot plot = new TrendPlot(host, w, true, new TrendPlot.Labels() {
            @Override public String value(double value) { return host.weight(value); }
            @Override public void select(TrendSeries.Sample sample) { }
        });
        panel.addView(plot, -1, host.ui.dp(54));
        LinearLayout dates = host.ui.row();
        TrendSeries.Sample first = w.samples.get(0), last = w.samples.get(count - 1);
        boolean sameInstant = first.x == last.x;
        String pattern = !sameInstant && !estimated ? "HH:mm" : "d MMM";
        SimpleDateFormat fmt = new SimpleDateFormat(pattern, Locale.UK);
        fmt.setTimeZone(TrendSeries.UTC);
        TextView start = host.ui.text(fmt.format(new Date(first.timeMs + first.offsetMinutes * 60_000L)), 12, MUTED);
        if (sameInstant) start.setGravity(Gravity.CENTER);
        dates.addView(start, Ui.flex());
        if (!sameInstant) dates.addView(host.ui.text(fmt.format(new Date(last.timeMs + last.offsetMinutes * 60_000L)), 12, MUTED));
        panel.addView(dates);
        return plot;
    }

    static TrendSeries series(List<Store.Entry> entries, boolean waist) {
        List<TrendSeries.Sample> samples = new ArrayList<>();
        for (Store.Entry e : entries) {
            Double value = waist ? e.waistCm : e.kg;
            if (value != null) samples.add(new TrendSeries.Sample(e.id, e.timeMs, e.offsetMinutes, value));
        }
        return new TrendSeries(samples);
    }

    private static long today() {
        long now = System.currentTimeMillis();
        return TrendSeries.localDay(now, TimeZone.getDefault().getOffset(now) / 60_000);
    }

    private String value(double v) { return showWaist ? String.format(Locale.UK, "%.1f cm", v) : host.weight(v); }

    private String change(double v) {
        String unit = showWaist ? "cm" : host.store.get("unit","kg");
        long tenths = showWaist ? Math.round(Math.abs(v) * 10) : Math.abs(WeightUnits.changeTenths(v,unit));
        String sign = tenths == 0 ? "" : v > 0 ? "+" : "-";
        return sign + (showWaist ? String.format(Locale.UK,"%.1f cm",tenths / 10.0) : WeightUnits.changeMagnitude(tenths,unit));
    }

    private void render() {
        page.removeAllViews();
        TextView title = host.ui.heading("Trends", 35);
        page.addView(title);
        if (!waist.samples.isEmpty() || !weight.samples.isEmpty()) {
            LinearLayout metrics = host.ui.row();
            metrics.setGravity(Gravity.CENTER_VERTICAL);
            addSpaced(page, metrics, 12);
            addChip(metrics, "Weight", !showWaist, () -> showWaist = false);
            addChip(metrics, "Waist", showWaist, () -> showWaist = true);
        }

        HorizontalScrollView rangeScroll = new HorizontalScrollView(host);
        rangeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout ranges = host.ui.row();
        rangeScroll.addView(ranges);
        addSpaced(page, rangeScroll, 8);
        for (TrendSeries.Range item : TrendSeries.Range.values()) {
            addChip(ranges, item.label, range == item, () -> range = item);
        }
        page.addView(readings);
        renderReadings();
    }

    private void renderReadings() {
        readings.removeAllViews();
        TrendSeries chosen = showWaist ? waist : weight;
        TrendSeries.Window window = chosen.window(range, today);
        LinearLayout chart = host.ui.card();
        addSpaced(readings, chart, 14);
        String metric = showWaist ? "waist" : "weight";
        if (window.samples.isEmpty()) {
            chart.addView(host.ui.text(chosen.samples.isEmpty() ? "Your first " + metric + " reading" : "No readings in this period", 21, PAPER));
            String hint = !chosen.samples.isEmpty() ? "Choose a longer period to see earlier measurements." : showWaist
                    ? "Log your waist measurement in centimetres to follow it alongside your weight." : "Weigh in or add a reading to start your chart.";
            addSpaced(chart, host.ui.text(hint, 15, MUTED), 8);
            if (chosen.samples.isEmpty()) addSpaced(chart, host.ui.button("Add a reading", host::manualEntry), 12);
            return;
        }

        boolean established = window.days.size() > 1;
        chart.addView(host.ui.text(established ? (showWaist ? "Waist trend" : "Weight trend") : "Latest reading in period", 15, MUTED));
        double last = established ? window.days.get(window.days.size() - 1).trend : window.samples.get(window.samples.size() - 1).value;
        TextView magnitude = host.ui.text(value(last), 34, PAPER);
        magnitude.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addSpaced(chart, magnitude, 1);
        Double delta = window.change();
        addSpaced(chart, host.ui.text(delta == null ? "A second measurement day will start your trend." : change(delta) + " trend change in this period", 14, MUTED), 3);
        TextView inspector = host.ui.text("Tap a point to inspect a reading.", 14, MUTED);
        inspector.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Button editReading = host.ui.button("Edit selected reading", () -> { });
        editReading.setVisibility(View.GONE);
        TrendPlot plot = new TrendPlot(host, window, false, new TrendPlot.Labels() {
            @Override public String value(double v) { return TrendsScreen.this.value(v); }
            @Override public void select(TrendSeries.Sample sample) {
                SimpleDateFormat fmt = new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK);
                fmt.setTimeZone(TrendSeries.UTC);
                String time = fmt.format(new Date(sample.timeMs + sample.offsetMinutes * 60_000L));
                inspector.setText(time + "  ·  " + TrendsScreen.this.value(sample.value));
                inspector.setTextColor(PAPER);
                editReading.setVisibility(View.VISIBLE);
                editReading.setOnClickListener(v -> {
                    Store.Entry entry = host.store.find(sample.entryId);
                    if (entry != null) host.editor(entry);
                    else host.refresh();
                });
            }
        });
        LinearLayout.LayoutParams plotSize = new LinearLayout.LayoutParams(-1, host.ui.dp(188));
        plotSize.topMargin = host.ui.dp(16);
        chart.addView(plot, plotSize);
        LinearLayout legend = host.ui.row();
        legend.setGravity(Gravity.CENTER_VERTICAL);
        boolean largeText = host.getResources().getConfiguration().fontScale > 1.3f;
        if (largeText) legend.setOrientation(LinearLayout.VERTICAL);
        TextView measured = host.ui.text("●  Reading", 13, PAPER);
        legend.addView(measured);
        if (established) {
            TextView estimate = host.ui.text("━  Estimated trend", 13, ACCENT);
            if (!largeText) estimate.setPadding(host.ui.dp(14), 0, 0, 0);
            legend.addView(estimate);
        }
        addSpaced(chart, legend, 12);
        addSpaced(chart, inspector, 8);
        addSpaced(chart, editReading, 8);
        if (!showWaist) addGoal(chart, chosen);

        LinearLayout week = host.ui.card();
        addSpaced(readings, week, 12);
        Calendar cal = new GregorianCalendar(TrendSeries.UTC, Locale.UK);
        cal.setTimeInMillis(today * TrendSeries.MILLIS_PER_DAY);
        int daysSinceMonday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        TrendSeries.Window thisWeek = new TrendSeries.Window(chosen, today - daysSinceMonday, today);
        week.addView(host.ui.text("This week", 18, PAPER));
        int n = thisWeek.days.size();
        Double weeklyAverage = thisWeek.meanOfDailyMedians();
        String summary = n == 0 ? "No " + metric + " readings this week yet." : n + (n == 1 ? " day" : " days") + " measured  ·  " + value(weeklyAverage) + " average";
        addSpaced(week, host.ui.text(summary, 15, MUTED), 4);

        TextView method = host.ui.text("How the trend is calculated", 15, ACCENT);
        method.setGravity(Gravity.CENTER_VERTICAL);
        method.setMinHeight(host.ui.dp(48));
        addSpaced(readings, method, 4);
        String explanation = "Each dot is a recorded measurement. For each day with readings, Scales takes the median and smooths it with a seven-day half-life. After a week's gap the next reading gets half the weight. The weekly average gives each measured day equal weight.";
        if (!showWaist) explanation += "\n\nToday compares the saved day’s weight trend with 7 and 14 days earlier. A missing comparison day can be estimated between two weigh-in days up to a week apart. Otherwise that comparison stays hidden.";
        TextView explainer = host.ui.text(explanation, 14, MUTED);
        explainer.setVisibility(View.GONE);
        readings.addView(explainer);
        method.setFocusable(true);
        method.setOnClickListener(v -> {
            boolean open = explainer.getVisibility() == View.VISIBLE;
            explainer.setVisibility(open ? View.GONE : View.VISIBLE);
            method.setText(open ? "How the trend is calculated" : "Hide calculation details");
        });
    }

    private void addGoal(LinearLayout chart, TrendSeries series) {
        Store prefs = host.store;
        TrendGoal goal = TrendGoal.read(prefs.get("goalMode", "track"), prefs.get("targetKg", ""),
                prefs.get("rangeLowKg", ""), prefs.get("rangeHighKg", ""));
        if (goal == null) return;
        String title = goal.range ? "Maintenance range  ·  " + value(goal.lowKg) + " – " + value(goal.highKg)
                : "Target weight  ·  " + value(goal.lowKg);
        addSpaced(chart, host.ui.heading(title, 16), 16);
        // The estimate always uses the full measurement history, regardless of the selected time range.
        TrendSeries.Window current = series.window(TrendSeries.Range.ALL, today);
        if (current.days.size() < 2) return;
        double kg = current.days.get(current.days.size() - 1).trend;
        double delta = goal.distanceKg(kg);
        String reference = goal.range ? "your range" : "your target";
        String relation;
        if (delta == 0) relation = (goal.range ? "within " : "at ") + reference;
        else {
            boolean pounds = !"kg".equals(prefs.get("unit", "kg"));
            double absolute = Math.abs(delta) / (pounds ? 0.45359237 : 1);
            String amount = absolute < 0.1 ? "less than 0.1" : String.format(Locale.UK, "%.1f", absolute);
            relation = amount + (pounds ? " lb " : " kg ") + (delta < 0 ? "below " : "above ") + reference;
        }
        addSpaced(chart, host.ui.text("Your estimated trend is " + relation + ".", 14, MUTED), 4);
    }

    private void addSpaced(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = host.ui.dp(top);
        parent.addView(view, lp);
    }

    private void addChip(LinearLayout parent, String text, boolean active, Runnable task) {
        TextView chip = host.ui.text(text, 14, active ? PAPER : MUTED);
        chip.setGravity(Gravity.CENTER);
        chip.setMinWidth(host.ui.dp(48));
        chip.setMinHeight(host.ui.dp(48));
        chip.setPadding(host.ui.dp(8), 0, host.ui.dp(8), 0);
        chip.setFocusable(true);
        styleChip(chip, active);
        String spoken = text;
        switch (text) {
            case "1W": spoken = "One week"; break;
            case "1M": spoken = "One month"; break;
            case "3M": spoken = "Three months"; break;
            case "1Y": spoken = "One year"; break;
            case "All": spoken = "All time"; break;
        }
        chip.setContentDescription(spoken);
        chip.setOnClickListener(v -> {
            if (chip.isSelected()) return;
            for (int i = 0; i < parent.getChildCount(); i++) {
                TextView peer = (TextView) parent.getChildAt(i);
                styleChip(peer, peer == chip);
            }
            task.run();
            renderReadings();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = host.ui.dp(3);
        parent.addView(chip, lp);
    }

    private void styleChip(TextView chip, boolean active) {
        chip.setSelected(active);
        chip.setTextColor(active ? PAPER : MUTED);
        chip.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        chip.setBackground(host.ui.ripple(active ? 0xFF33233E : Ui.BG, 14, active ? 0xFFAA86C5 : 0));
    }
}
