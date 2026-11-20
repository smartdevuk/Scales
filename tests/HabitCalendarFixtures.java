package uk.co.violetscale.probe;

import java.time.Instant;
import java.util.TimeZone;

public final class HabitCalendarFixtures {
    private static final TimeZone LONDON = TimeZone.getTimeZone("Europe/London");
    private static long time(String iso) { return Instant.parse(iso).toEpochMilli(); }
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        equal("2026-11-20", HabitCalendar.dayKey(time("2026-11-20T23:00:00Z"), LONDON));
        equal("2026-06-21", HabitCalendar.dayKey(time("2026-06-20T23:01:00Z"), LONDON));
        equal(time("2026-11-16T00:00:00Z"), HabitCalendar.mondayStart(time("2026-11-22T23:59:59Z"), LONDON));
        equal(time("2026-11-23T00:00:00Z"), HabitCalendar.mondayStart(time("2026-11-23T00:00:00Z"), LONDON));
        equal(time("2026-03-23T00:00:00Z"), HabitCalendar.mondayStart(time("2026-03-29T16:00:00Z"), LONDON));
        equal(time("2026-03-29T23:00:00Z"), HabitCalendar.mondayStart(time("2026-03-30T06:30:00Z"), LONDON));
        equal(time("2026-10-25T08:30:00Z"), HabitCalendar.nextReminder(time("2026-10-24T22:00:00Z"), 510, LONDON));
        equal(time("2026-03-29T07:30:00Z"), HabitCalendar.nextReminder(time("2026-03-28T12:00:00Z"), 510, LONDON));
        equal(time("2026-03-29T01:30:00Z"), HabitCalendar.nextReminder(time("2026-03-28T12:00:00Z"), 90, LONDON));
        equal(time("2026-03-30T00:30:00Z"), HabitCalendar.nextReminder(time("2026-03-29T03:00:00Z"), 90, LONDON));
        equal(time("2026-11-21T08:00:00Z"), HabitCalendar.nextReminder(time("2026-11-20T08:00:00Z"), 480, LONDON));
        equal(time("2026-11-20T08:00:00Z"), HabitCalendar.nextReminder(time("2026-11-20T07:59:00Z"), 480, LONDON));
        equal(0, HabitCalendar.boundedSetting("8",0,7,0));
        equal(5, HabitCalendar.boundedSetting("5",0,7,0));
        equal(480, HabitCalendar.boundedSetting("invalid",0,1439,480));
        System.out.println("15 habit calendar checks passed.");
    }
}
