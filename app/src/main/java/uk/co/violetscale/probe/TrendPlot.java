package uk.co.violetscale.probe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.view.animation.DecelerateInterpolator;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Canvas chart with real observations kept distinct from the estimated trajectory. */
final class TrendPlot extends View {
    interface Labels {
        String value(double value);
        void select(TrendSeries.Sample sample);
    }

    private final TrendSeries.Window data;
    private final boolean compact;
    private final Labels labels;
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private final DashPathEffect gap;
    private final String[] ticks = new String[3];
    private String startLabel, endLabel;
    private static final int LINE = 0xFFB68AFF, POINT = 0xFFF5F0FF, GRID = 0xFF39333F;
    private float left, right, top, bottom, downX, downY;
    private double min, max;
    private double domainStart, domainEnd;
    private LinearGradient fill;
    private ValueAnimator animator;
    private float reveal = 1f, pulse = 1f;
    private TrendSeries.Sample newPoint;
    private int selected = -1;
    private boolean scrubbing;

    TrendPlot(Context context, TrendSeries.Window data, boolean compact, Labels labels) {
        super(context);
        this.data = data;
        this.compact = compact;
        this.labels = labels;
        density = getResources().getDisplayMetrics().density;
        gap = new DashPathEffect(new float[]{dp(3), dp(5)}, 0);
        domainStart = data.from;
        domainEnd = data.to + 1.0;
        // A compact plot fills its width with the readings in its labelled window.
        // A single instant stays centred and never implies a span of observations.
        if (compact && !data.samples.isEmpty()) {
            domainStart = data.samples.get(0).x;
            domainEnd = data.samples.get(data.samples.size() - 1).x;
        }
        setFocusable(!compact);
        setClickable(!compact);
        setContentDescription(compact ? data.samples.size() + (data.samples.size() == 1 ? " weight reading" : " weight readings") + " in the past month"
                : "Measurement chart. Select a point or use next and previous to inspect readings.");
        min = Double.POSITIVE_INFINITY;
        max = Double.NEGATIVE_INFINITY;
        for (TrendSeries.Sample s : data.samples) include(s.value);
        for (TrendSeries.Day d : data.days) include(d.trend);
        if (data.samples.isEmpty()) { min = 0; max = 1; }
        double margin = Math.max(0.15, (max - min) * 0.16);
        if (max - min < 1.0) margin = Math.max(margin, (1.0 - (max - min)) / 2);
        min -= margin;
        max += margin;
        if (!compact) {
            for (int i = 0; i <= 2; i++) ticks[i] = labels.value(min + (max - min) * i / 2);
            SimpleDateFormat format = new SimpleDateFormat(data.to - data.from >= 365 ? "MMM yy" : "d MMM", Locale.UK);
            format.setTimeZone(TrendSeries.UTC);
            startLabel = format.format(new Date(data.from * TrendSeries.MILLIS_PER_DAY));
            endLabel = format.format(new Date(data.to * TrendSeries.MILLIS_PER_DAY));
        }
    }

    private void include(double value) { min = Math.min(min, value); max = Math.max(max, value); }
    private float dp(float value) { return density * value; }
    private float x(double localTime) {
        if (domainStart == domainEnd) return (left + right) / 2;
        return left + (float) ((localTime - domainStart) / (domainEnd - domainStart)) * (right - left);
    }
    private float y(double value) { return bottom - (float) ((value - min) / (max - min)) * (bottom - top); }

    private void stroke(int colour, float width) {
        pen.setColor(colour);
        pen.setStrokeWidth(dp(width));
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeCap(Paint.Cap.ROUND);
        pen.setStrokeJoin(Paint.Join.ROUND);
    }

    boolean hasPoint(String entryId) { return findPoint(entryId) != null; }

    private TrendSeries.Sample findPoint(String entryId) {
        if (entryId != null) for (TrendSeries.Sample s : data.samples) {
            if (entryId.equals(s.entryId)) return s;
        }
        return null;
    }

    /** A normal visit reveals the trace. A fresh observation gets one halo at its actual coordinates. */
    void playArrival(String entryId) {
        if (!compact || data.samples.isEmpty()) return;
        stopAnimation();
        newPoint = findPoint(entryId);
        if (entryId != null && newPoint == null) { invalidate(); return; }
        boolean pointAdded = newPoint != null;
        boolean motion = Build.VERSION.SDK_INT >= 26 ? ValueAnimator.areAnimatorsEnabled()
                : Settings.Global.getFloat(getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        if (!motion) { invalidate(); return; }
        if (pointAdded) pulse = 0f; else reveal = 0f;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.setDuration(pointAdded ? 720 : data.days.size() < 2 ? 300 : 570);
        animator.addUpdateListener(a -> {
            float fraction = (Float) a.getAnimatedValue();
            if (pointAdded) pulse = fraction; else reveal = fraction;
            invalidate();
        });
        animator.start();
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator.removeAllUpdateListeners();
            animator = null;
        }
        reveal = pulse = 1f;
    }

    @Override protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (h > 0) fill = new LinearGradient(0, 0, 0, h, 0x42AD77FC, 0x02AD77FC, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float font = 11 * getResources().getDisplayMetrics().scaledDensity;
        pen.setTextSize(font);
        float rail = 0;
        if (!compact) {
            for (String label : ticks) rail = Math.max(rail, pen.measureText(label));
        }
        left = dp(compact ? 10 : 4);
        right = Math.max(left + dp(12), getWidth() - (compact ? dp(10) : rail + dp(12)));
        top = compact ? dp(10) : font;
        bottom = Math.max(top + dp(8), getHeight() - (compact ? dp(10) : font + dp(12)));
        if (!compact) {
            for (int i = 0; i <= 2; i++) {
                double value = min + (max - min) * i / 2;
                float tick = y(value);
                stroke(GRID, 1);
                canvas.drawLine(left, tick, right, tick, pen);
                pen.setColor(0xFFC1B9CD);
                pen.setStyle(Paint.Style.FILL);
                pen.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(ticks[i], right + dp(8), tick + font * 0.33f, pen);
            }
            boolean bothFit = pen.measureText(startLabel) + pen.measureText(endLabel) + dp(8) < right - left;
            if (bothFit || data.from == data.to) canvas.drawText(startLabel, left, getHeight() - dp(2), pen);
            if (data.from != data.to) {
                pen.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(endLabel, right, getHeight() - dp(2), pen);
            }
        }
        canvas.save();
        if (reveal < 1f && data.days.size() > 1)
            canvas.clipRect(0, 0, left + (right - left) * reveal, getHeight());
        if (data.days.size() > 1) {
            TrendSeries.Day first = data.days.get(0);
            TrendSeries.Day last = data.days.get(data.days.size() - 1);
            path.reset();
            path.moveTo(x(first.x), bottom);
            for (TrendSeries.Day d : data.days) path.lineTo(x(d.x), y(d.trend));
            path.lineTo(x(last.x), bottom);
            path.close();
            pen.setStyle(Paint.Style.FILL);
            pen.setColor(0xffffffff);
            pen.setShader(fill);
            canvas.drawPath(path, pen);
            pen.setShader(null);
            stroke(LINE, compact ? 1.8f : 2.5f);
            TrendSeries.Day previous = first;
            for (int i = 1; i < data.days.size(); i++) {
                TrendSeries.Day d = data.days.get(i);
                pen.setPathEffect(d.day - previous.day > 7 ? gap : null);
                canvas.drawLine(x(previous.x), y(previous.trend), x(d.x), y(d.trend), pen);
                previous = d;
            }
            pen.setPathEffect(null);
        }
        pen.setStyle(Paint.Style.FILL);
        pen.setColor(compact ? 0xCCDFD5EB : POINT);
        if (compact && data.days.size() == 1) pen.setAlpha(Math.round(76 + 128 * reveal));
        for (TrendSeries.Sample s : data.samples) canvas.drawCircle(x(s.x), y(s.value), dp(compact ? 2.4f : 2.6f), pen);
        canvas.restore();
        if (newPoint != null) {
            float px = x(newPoint.x), py = y(newPoint.value);
            if (pulse < 1f) {
                stroke(0xFFCEB0FF, 1.8f);
                pen.setAlpha(Math.round(210 * (1 - pulse)));
                canvas.drawCircle(px, py, dp(3.6f + 6.1f * pulse), pen);
            }
            pen.setStyle(Paint.Style.FILL);
            pen.setColor(0xFFD2B8FF);
            canvas.drawCircle(px, py, dp(3.5f), pen);
        }
        if (selected >= 0) {
            TrendSeries.Sample point = data.samples.get(selected);
            float px = x(point.x), py = y(point.value);
            stroke(0xAA9E81BA, 1);
            canvas.drawLine(px, top, px, bottom, pen);
            pen.setStyle(Paint.Style.FILL);
            pen.setColor(0xFF201729);
            canvas.drawCircle(px, py, dp(6), pen);
            stroke(POINT, 2);
            canvas.drawCircle(px, py, dp(5), pen);
        }
    }

    private void pick(float touchX, float touchY) {
        int best = -1;
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < data.samples.size(); i++) {
            TrendSeries.Sample s = data.samples.get(i);
            double dx = touchX - x(s.x), dy = touchY - y(s.value);
            double d = dx * dx + dy * dy;
            if (d < distance) { distance = d; best = i; }
        }
        if (best >= 0) select(best);
    }

    private boolean select(int index) {
        if (index < 0 || index >= data.samples.size() || index == selected) return false;
        selected = index;
        labels.select(data.samples.get(index));
        invalidate();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (compact || data.samples.isEmpty()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX(); downY = event.getY(); scrubbing = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scrubbing && Math.abs(event.getX() - downX) > dp(5) && Math.abs(event.getX() - downX) > Math.abs(event.getY() - downY)) {
                    scrubbing = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (scrubbing) pick(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                pick(event.getX(), event.getY());
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                scrubbing = false;
                return true;
            default: return super.onTouchEvent(event);
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        if (selected < 0) select(0);
        return true;
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return select(selected + 1);
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) return select(selected < 0 ? data.samples.size() - 1 : selected - 1);
        return super.onKeyDown(key, event);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (compact || data.samples.isEmpty()) return;
        info.setScrollable(true);
        if (selected + 1 < data.samples.size()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        if (selected != 0 && data.samples.size() > 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return select(selected + 1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return select(selected < 0 ? data.samples.size() - 1 : selected - 1);
        return super.performAccessibilityAction(action, args);
    }
}
