package uk.co.violetscale.probe;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/** Bluetooth SIG Weight Measurement (2A9D), as specified by WSS 1.0.1 section 3.2. */
final class SigWeight {
    static final class Reading {
        final double kg;
        final String originalValue, originalUnit, originalResolution, sampleKey;
        final Long deviceTimeMs;
        final Integer userId;

        private Reading(BigDecimal weight, boolean pounds, Long time, Integer user, String sampleKey) {
            originalValue = weight.stripTrailingZeros().toPlainString();
            originalUnit = pounds ? "lb" : "kg";
            // Encoding increments. The server's physical weight resolution can be coarser.
            originalResolution = pounds ? "0.01" : "0.005";
            kg = pounds ? weight.multiply(new BigDecimal("0.45359237")).doubleValue() : weight.doubleValue();
            deviceTimeMs = time;
            userId = user;
            this.sampleKey = sampleKey;
        }
    }

    static Reading decode(byte[] value, TimeZone localZone) {
        if (value == null || value.length < 3) return null;
        int flags = value[0] & 255;
        boolean pounds = (flags & 1) != 0, dated = (flags & 2) != 0, hasUser = (flags & 4) != 0;
        int length = 3 + (dated ? 7 : 0) + (hasUser ? 1 : 0) + ((flags & 8) == 0 ? 0 : 4);
        if ((flags & 0xF0) != 0 || value.length != length) return null;
        int rawWeight = uint16(value, 1);
        if (rawWeight == 0 || rawWeight == 0xFFFF) return null;
        BigDecimal weight = pounds ? BigDecimal.valueOf(rawWeight, 2) : BigDecimal.valueOf(rawWeight * 5L, 3);
        Integer user = hasUser ? Integer.valueOf(value[dated ? 10 : 3] & 255) : null;
        Long time = null;
        String key = "";
        if (dated) {
            // Date Time is the scale's local clock. It carries neither UTC offset nor time-zone ID.
            int year = uint16(value, 3), month = value[5] & 255, day = value[6] & 255;
            int hour = value[7] & 255, minute = value[8] & 255, second = value[9] & 255;
            if (year < 1582 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31
                    || hour > 23 || minute > 59 || second > 59) return null;
            Calendar clock = new GregorianCalendar(localZone, Locale.UK);
            clock.clear();
            clock.setLenient(false);
            clock.set(year, month - 1, day, hour, minute, second);
            try { time = clock.getTimeInMillis(); }
            catch (IllegalArgumentException invalidDate) { return null; }
            // Omit optional BMI/height and the phone's timezone so a re-sent record has a stable key.
            key = year + "-" + month + "-" + day + "T" + hour + "-" + minute + "-" + second
                    + "/" + (user == null ? "none" : user) + "/" + (pounds ? "lb" : "kg") + "/" + rawWeight;
        }
        return new Reading(weight, pounds, time, user, key);
    }

    private static int uint16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }
}
