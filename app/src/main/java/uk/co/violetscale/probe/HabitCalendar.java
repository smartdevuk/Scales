package uk.co.violetscale.probe;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Calendar arithmetic uses the phone's local days, including daylight-saving changes. */
final class HabitCalendar {
    private HabitCalendar() {}

    static String dayKey(long time, TimeZone zone) {
        Calendar local = Calendar.getInstance(zone);
        local.setTimeInMillis(time);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", local.get(Calendar.YEAR),
                local.get(Calendar.MONTH) + 1, local.get(Calendar.DAY_OF_MONTH));
    }

    static long mondayStart(long time, TimeZone zone) {
        Calendar local = Calendar.getInstance(zone);
        local.setTimeInMillis(time);
        int daysSinceMonday = (local.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        local.add(Calendar.DATE, -daysSinceMonday);
        local.set(Calendar.HOUR_OF_DAY, 0);
        local.set(Calendar.MINUTE, 0);
        local.set(Calendar.SECOND, 0);
        local.set(Calendar.MILLISECOND, 0);
        return local.getTimeInMillis();
    }

    static long nextReminder(long now, int minuteOfDay, TimeZone zone) {
        Calendar local = Calendar.getInstance(zone);
        local.setTimeInMillis(now);
        setTime(local, minuteOfDay);
        // Reset the intended clock time tomorrow even if today's chosen hour did not exist.
        if (local.getTimeInMillis() <= now) {
            local.add(Calendar.DATE, 1);
            setTime(local, minuteOfDay);
        }
        return local.getTimeInMillis();
    }

    private static void setTime(Calendar day, int minuteOfDay) {
        day.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        day.set(Calendar.MINUTE, minuteOfDay % 60);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
    }

    static int boundedSetting(String raw, int min, int max, int fallback) {
        try {
            int value = Integer.parseInt(raw);
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }
}
