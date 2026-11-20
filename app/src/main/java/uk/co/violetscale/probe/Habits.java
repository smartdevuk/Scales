package uk.co.violetscale.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.WeakHashMap;

public final class Habits {
    private static final int TEXT = 0xfff4f0fc, MUTED = 0xffbbb4ca, VIOLET = 0xffcfafff;
    private static final String SELECTION = "habits.selection.v1";
    private static final String GOAL = "habits.weeklyWeighDays";
    private static final WeakHashMap<Activity, WeakReference<Runnable>> openControls = new WeakHashMap<>();
    private static final String[][] SUGGESTED = {
            {"strength", "Resistance training"}, {"protein", "Include protein with a meal"},
            {"veg", "Include vegetables with a meal"}, {"walk", "Take a walk"},
            {"plan", "Plan a balanced meal"}
    };

    private Habits() {}

    public static View today(Activity a, Store store, Runnable changed) {
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        List<Habit> chosen = new ArrayList<>();
        for (Habit habit : options(store)) if (habit.active) chosen.add(habit);
        int target = weeklyGoal(store);
        LinearLayout panel = column(a);
        shape(panel, 0xff15111c, 0xff332b40);
        panel.setPadding(dp(a,16), dp(a,7), dp(a,16), dp(a,10));
        if (target == 0 && chosen.isEmpty()) {
            panel.addView(action(a, "Add habits or a weekly goal", () -> settings(a, store, changed)));
            return panel;
        }
        LinearLayout heading = new LinearLayout(a);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label(a, "This week", 19, TEXT, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(action(a, "Manage", () -> settings(a, store, changed)));
        panel.addView(heading);
        if (target > 0) {
            Set<String> days = weighDays(store, now, zone);
            int count = days.size();
            String status = count >= target ? "Weekly goal reached" : "Weekly weighing goal";
            panel.addView(label(a, status, 14, MUTED, false));
            panel.addView(label(a, count + (count == 1 ? " day weighed" : " days weighed")
                    + "  ·  Goal " + target, 16, TEXT, true));
        }
        if (!chosen.isEmpty()) {
            TextView title = label(a, "Today’s habits", 15, MUTED, false);
            title.setPadding(0, dp(a,10), 0, 0);
            panel.addView(title);
            String day = HabitCalendar.dayKey(now, zone);
            for (Habit habit : chosen) {
                String key = checkKey(habit.id, day);
                CheckBox row = check(a, habit.label);
                boolean done = "1".equals(store.get(key, ""));
                row.setChecked(done);
                int count = habitDays(store, habit.id, now, zone);
                row.setText(habit.label + "\n" + count + (count == 1 ? " day this week" : " days this week"));
                row.setOnCheckedChangeListener((box, checked) -> {
                    store.put(key, checked ? "1" : "");
                    changed.run();
                });
                panel.addView(row);
            }
        }
        return panel;
    }

    public static void settings(Activity a, Store store, Runnable changed) {
        ScrollView scroll = new ScrollView(a);
        LinearLayout body = column(a);
        body.setPadding(dp(a,20), dp(a,4), dp(a,20), dp(a,12));
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle("Habits & reminders")
                .setView(scroll).setPositiveButton("Done", null).create();
        Runnable redraw = new Runnable() {
            @Override public void run() {
                body.removeAllViews();
                body.addView(section(a, "Weekly weighing goal"));
                int target = weeklyGoal(store);
                body.addView(action(a, target == 0 ? "Choose days per week" :
                        target + (target == 1 ? " day per week" : " days per week"), () -> {
                    CharSequence[] choices = {"Off", "1 day", "2 days", "3 days", "4 days", "5 days", "6 days", "7 days"};
                    new AlertDialog.Builder(a).setTitle("Days to weigh each week")
                            .setSingleChoiceItems(choices, target, (choice, index) -> {
                                store.put(GOAL, Integer.toString(index));
                                choice.dismiss();
                                run();
                            }).setNegativeButton("Cancel", null).show();
                }));
                body.addView(section(a, "Habits to track"));
                List<Habit> items = options(store);
                for (Habit habit : items) {
                    CheckBox row = check(a, habit.label);
                    row.setChecked(habit.active);
                    row.setOnCheckedChangeListener((box, checked) -> {
                        habit.active = checked;
                        persist(store, items);
                    });
                    body.addView(row);
                }
                body.addView(action(a, "Add a custom habit", () -> custom(a, store, items, this)));
                body.addView(section(a, "Weigh-in reminder"));
                boolean enabled = ReminderReceiver.enabled(store);
                boolean allowed = ReminderReceiver.allowed(a);
                int clock = ReminderReceiver.minute(store);
                if (enabled) {
                    body.addView(label(a, allowed ? "Skips days you’ve already weighed and weeks you’ve met your goal."
                            : "Notifications are switched off for Scales.", 15, MUTED, false));
                    if (!allowed) body.addView(action(a, "Open notification settings", () -> systemSettings(a)));
                    body.addView(action(a, "Change time · " + clockText(a,clock), () -> time(a,store,this)));
                    body.addView(action(a, "Pause reminder", () -> {
                        store.put(ReminderReceiver.ENABLED, "0");
                        ReminderReceiver.refresh(a);
                        run();
                    }));
                } else {
                    body.addView(action(a, "Set a reminder", () -> time(a,store,this)));
                }
            }
        };
        redraw.run();
        openControls.put(a, new WeakReference<>(redraw));
        dialog.setOnDismissListener(d -> {
            openControls.remove(a);
            changed.run();
        });
        dialog.show();
    }

    public static void notificationChanged(Activity a) {
        ReminderReceiver.refresh(a);
        WeakReference<Runnable> controls = openControls.get(a);
        Runnable redraw = controls == null ? null : controls.get();
        if (redraw != null) redraw.run();
    }

    private static void custom(Activity a, Store store, List<Habit> items, Runnable redraw) {
        EditText name = new EditText(a);
        name.setSingleLine(true);
        name.setHint("Habit name");
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        LinearLayout body = column(a);
        body.setPadding(dp(a,20), 0, dp(a,20), 0);
        body.addView(name);
        AlertDialog ask = new AlertDialog.Builder(a).setTitle("Custom habit")
                .setView(body).setNegativeButton("Cancel", null).setPositiveButton("Add", null).create();
        ask.setOnShowListener(d -> ask.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String chosen = name.getText().toString().trim();
            if (chosen.isEmpty()) { name.setError("Enter a habit name"); return; }
            for (Habit habit : items) {
                if (habit.label.equalsIgnoreCase(chosen)) {
                    name.setError("This habit is already in your list"); return;
                }
            }
            items.add(new Habit(UUID.randomUUID().toString(), chosen, true));
            persist(store, items);
            ask.dismiss();
            redraw.run();
        }));
        ask.show();
    }

    private static void time(Activity a, Store store, Runnable redraw) {
        int old = ReminderReceiver.minute(store);
        new TimePickerDialog(a, (picker,hour,min) -> {
            store.put(ReminderReceiver.MINUTE, Integer.toString(hour*60 + min));
            store.put(ReminderReceiver.ENABLED, "1");
            ReminderReceiver.prepareChannel(a);
            if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, ReminderReceiver.REQUEST);
            }
            ReminderReceiver.refresh(a);
            redraw.run();
        }, old/60, old%60, DateFormat.is24HourFormat(a)).show();
    }

    private static String clockText(Activity a, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minute / 60);
        c.set(Calendar.MINUTE, minute % 60);
        return DateFormat.getTimeFormat(a).format(new Date(c.getTimeInMillis()));
    }

    private static void systemSettings(Activity a) {
        Intent settings;
        if (Build.VERSION.SDK_INT >= 26) {
            settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
        } else {
            settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", a.getPackageName(), null));
        }
        a.startActivity(settings);
    }

    static int weeklyGoal(Store store) {
        return HabitCalendar.boundedSetting(store.get(GOAL, "0"), 0, 7, 0);
    }

    static Set<String> weighDays(Store store, long now, TimeZone zone) {
        Set<String> days = new HashSet<>();
        long monday = HabitCalendar.mondayStart(now, zone);
        for (long time : store.weightTimesSince(monday, now)) days.add(HabitCalendar.dayKey(time, zone));
        return days;
    }

    private static int habitDays(Store store, String id, long now, TimeZone zone) {
        int days = 0;
        Calendar date = Calendar.getInstance(zone);
        date.setTimeInMillis(HabitCalendar.mondayStart(now,zone));
        while (date.getTimeInMillis() <= now) {
            if ("1".equals(store.get(checkKey(id,HabitCalendar.dayKey(date.getTimeInMillis(),zone)), ""))) days++;
            date.add(Calendar.DATE, 1);
        }
        return days;
    }

    private static String checkKey(String id, String day) { return "habits.check." + id + "." + day; }

    private static class Habit {
        final String id, label;
        boolean active;
        Habit(String id, String label, boolean active) { this.id=id; this.label=label; this.active=active; }
    }

    static boolean validSelection(String stored) {
        try {
            parseSelection(stored);
            return true;
        } catch (JSONException invalid) {
            return false;
        }
    }

    private static List<Habit> parseSelection(String stored) throws JSONException {
        ArrayList<Habit> items = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        JSONArray list = new JSONArray(stored);
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            String id = item.getString("id");
            String label = item.getString("label");
            if (!id.matches("[a-z0-9-]{1,64}") || !ids.add(id) || label.trim().isEmpty()
                    || label.length() > 64 || label.indexOf('\u0000') >= 0 || !(item.get("active") instanceof Boolean))
                throw new JSONException("Invalid habit");
            items.add(new Habit(id, label, item.getBoolean("active")));
        }
        return items;
    }

    private static List<Habit> options(Store store) {
        String stored = store.get(SELECTION, "");
        try {
            return parseSelection(stored);
        } catch (JSONException unreadable) {
            List<Habit> items = new ArrayList<>();
            for (String[] item : SUGGESTED) items.add(new Habit(item[0],item[1],false));
            return items;
        }
    }

    private static void persist(Store store, List<Habit> items) {
        JSONArray list = new JSONArray();
        try {
            for (Habit habit : items) list.put(new JSONObject().put("id",habit.id)
                    .put("label",habit.label).put("active",habit.active));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        store.put(SELECTION, list.toString());
    }

    private static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private static TextView label(Context c, String title, int size, int colour, boolean bold) {
        TextView v = new TextView(c);
        v.setText(title); v.setTextSize(size); v.setTextColor(colour);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private static TextView section(Activity a, String title) {
        TextView v = label(a,title,17,TEXT,true);
        v.setPadding(0, dp(a,12), 0, dp(a,4));
        return v;
    }

    private static Button action(Activity a, String title, Runnable callback) {
        Button b = new Button(a, null, android.R.attr.borderlessButtonStyle);
        b.setText(title); b.setTextColor(VIOLET); b.setTextSize(15); b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); b.setMinWidth(0);
        b.setMinimumHeight(dp(a,48)); b.setPadding(dp(a,4), 0, dp(a,4), 0);
        b.setOnClickListener(v -> callback.run());
        return b;
    }

    private static CheckBox check(Activity a, String title) {
        CheckBox row = new CheckBox(a);
        row.setText(title); row.setTextColor(TEXT); row.setTextSize(15);
        row.setMinimumHeight(dp(a,48)); row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a,8), dp(a,4), dp(a,4), dp(a,4));
        row.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},
                new int[]{VIOLET,MUTED}));
        return row;
    }

    private static void shape(View v, int colour, int border) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(colour); background.setCornerRadius(dp(v.getContext(),18));
        background.setStroke(dp(v.getContext(),1),border); v.setBackground(background);
    }
    private static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
}
