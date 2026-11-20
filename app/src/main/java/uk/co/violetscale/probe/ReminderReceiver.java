package uk.co.violetscale.probe;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Set;
import java.util.TimeZone;

public final class ReminderReceiver extends BroadcastReceiver {
    static final String ENABLED = "habits.reminder.enabled";
    static final String MINUTE = "habits.reminder.minute";
    private static final String LAST_DAY = "local.reminder.lastDay";
    public static final int REQUEST = 3104;
    private static final String ACTION = "uk.co.violetscale.probe.WEIGH_REMINDER";
    private static final String CHANNEL = "violet-weigh-reminders";
    private static final int NOTIFICATION = 3105;

    static boolean enabled(Store store) { return "1".equals(store.get(ENABLED, "0")); }
    static int minute(Store store) {
        return HabitCalendar.boundedSetting(store.get(MINUTE, "480"), 0, 1439, 480);
    }

    static void prepareChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Weigh-in reminders",
                    NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }
    }

    static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    public static void refresh(Context context) {
        Store store = new Store(context);
        schedule(context, store);
    }

    public static void measurementChanged(Context context) {
        Store store = new Store(context);
        if (enabled(store) && !reminderNeeded(store, System.currentTimeMillis(), TimeZone.getDefault())) {
            context.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        }
        schedule(context, store);
    }

    private static boolean reminderNeeded(Store store, long now, TimeZone zone) {
        Set<String> days = Habits.weighDays(store, now, zone);
        int target = Habits.weeklyGoal(store);
        return !days.contains(HabitCalendar.dayKey(now, zone)) && (target == 0 || days.size() < target);
    }

    private static void schedule(Context context, Store store) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        Intent wake = new Intent(context,ReminderReceiver.class).setAction(ACTION);
        if (!enabled(store) || !allowed(context)) {
            PendingIntent existing = PendingIntent.getBroadcast(context, REQUEST, wake,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (existing != null) { alarms.cancel(existing); existing.cancel(); }
            context.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
            return;
        }
        prepareChannel(context);
        TimeZone zone = TimeZone.getDefault();
        long next = HabitCalendar.nextReminder(System.currentTimeMillis(),minute(store),zone);
        wake.putExtra("day",HabitCalendar.dayKey(next,zone));
        PendingIntent alarm = PendingIntent.getBroadcast(context,REQUEST,wake,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // An inexact local reminder tolerates battery-saving delays and needs no exact-alarm access.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,alarm);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Store store = new Store(context);
        if (ACTION.equals(intent.getAction()) && enabled(store) && allowed(context)) {
            long now = System.currentTimeMillis();
            TimeZone zone = TimeZone.getDefault();
            String today = HabitCalendar.dayKey(now,zone);
            if (today.equals(intent.getStringExtra("day")) && !today.equals(store.get(LAST_DAY, ""))) {
                if (reminderNeeded(store, now, zone)) {
                    if (notifyUser(context)) store.put(LAST_DAY,today);
                }
            }
        }
        schedule(context,store);
    }

    private static boolean notifyUser(Context context) {
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open == null) return false;
        open.setAction("uk.co.violetscale.WEIGH");
        PendingIntent launch = PendingIntent.getActivity(context,REQUEST,open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        prepareChannel(context);
        Notification.Builder notice;
        if (Build.VERSION.SDK_INT >= 26) notice = new Notification.Builder(context,CHANNEL);
        else notice = new Notification.Builder(context);
        notice.setSmallIcon(R.drawable.ic_reminder).setColor(0xff9162d2)
                .setContentTitle("Time for a check-in")
                .setContentText("Open Scales when you’re ready to weigh.")
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setLocalOnly(true).setAutoCancel(true).setContentIntent(launch);
        try {
            context.getSystemService(NotificationManager.class).notify(NOTIFICATION,notice.build());
            return true;
        } catch (SecurityException permissionChanged) {
            return false;
        }
    }
}
