package uk.co.violetscale.probe;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Independent decoders for the specific clear advertisements listed in docs/BROADCAST-PROTOCOLS.md. */
public final class ScaleBroadcast {
    public static final String XIAOMI_V1 = "xiaomi-mi-v1";
    public static final String XIAOMI_V2 = "xiaomi-mi-body-v2";
    public static final String OKOK_C0 = "okok-c0-0a01";
    public static final String OKOK_C0_MAC = "okok-c0-mac";
    public static final String ICOMON_AAA = "icomon-aaa";
    private static final int SERVICE_WEIGHT = 0x181d, SERVICE_COMPOSITION = 0x181b;
    private static final int ICOMON_MANUFACTURER = -1;
    private static final BigDecimal LB_TO_KG = new BigDecimal("0.45359237");

    private ScaleBroadcast() { }

    public static final class Reading {
        public final String protocol, model, originalUnit, originalValue, originalResolution, sessionKey;
        public final Double kg, impedanceOhms;
        public final Integer impedanceRaw;
        // None of these frames carries the offset of its local clock.
        public final Long measuredAtMs = null;
        public final boolean stable, start;

        private Reading(String protocol, String model, boolean stable, boolean start, String unit,
                        String resolution, BigDecimal value, String key, Integer rawZ, Double ohms) {
            this.protocol = protocol;
            this.model = model;
            this.stable = stable;
            this.start = start;
            originalUnit = unit;
            originalResolution = resolution;
            originalValue = value == null ? null : value.stripTrailingZeros().toPlainString();
            kg = value == null ? null : ("lb".equals(unit) ? value.multiply(LB_TO_KG) : value).doubleValue();
            sessionKey = key;
            impedanceRaw = rawZ;
            impedanceOhms = ohms;
        }
    }

    public static Reading decode(byte[] record, String advertisedName) {
        return decode(record, advertisedName, null);
    }

    /** The optional name and address must belong to this scan result's device. */
    public static Reading decode(byte[] record, String advertisedName, String address) {
        if (record == null) return null;
        String completeName = null;
        int frame = -1, service = 0;
        for (int pos = 0; pos < record.length;) {
            int length = record[pos++] & 255;
            if (length == 0) {
                while (pos < record.length) if (record[pos++] != 0) return null;
                break;
            }
            if (length > record.length - pos) return null;
            int type = record[pos] & 255;
            if (type == 9) {
                if (completeName != null) return null;
                completeName = new String(record, pos + 1, length - 1, StandardCharsets.UTF_8);
            } else if (type == 0x16) {
                if (length < 3) return null;
                int uuid = little(record, pos + 1);
                if (uuid == SERVICE_WEIGHT || uuid == SERVICE_COMPOSITION) {
                    int expectedLength = uuid == SERVICE_WEIGHT ? 13 : 16;
                    if (length != expectedLength || frame >= 0) return null;
                    service = uuid;
                    frame = pos + 3;
                }
            } else if (type == 0xff) {
                int m = pos + 1;
                if (length >= 15 && sameAddress(record, m + 2, address, true)
                        && ((record[m + 12] ^ record[m + 1]) & 255) == 0xad) {
                    if (frame >= 0) return null;
                    service = ICOMON_MANUFACTURER;
                    frame = m;
                } else if (length >= 2 && (record[m] & 255) == 0xc0) {
                    if (length != 16 || frame >= 0) return null;
                    frame = m;
                }
            }
            pos += length;
        }
        if (frame < 0) return null;
        String name = completeName == null ? advertisedName : completeName;
        if (service == 0) return chipsea(record, frame, name, address);
        if (service == ICOMON_MANUFACTURER) return icomon(record, frame, name);
        if (name == null) return null;
        name = name.toUpperCase(Locale.ROOT);
        if (service == SERVICE_WEIGHT && ("MI_SCALE".equals(name) || "MI SCALE2".equals(name))) {
            return xiaomi(record, frame, false);
        }
        if (service == SERVICE_COMPOSITION && ("MIBFS".equals(name) || "MIBCS".equals(name)
                || "MI SCALE2".equals(name))) {
            return xiaomi(record, frame, true);
        }
        return null;
    }

    private static Reading xiaomi(byte[] data, int p, boolean bodyScale) {
        int header = data[p] & 255;
        int unit = bodyScale ? header : header & 0x1f;
        if (unit != 2 && unit != 3) return null;
        int flags = bodyScale ? data[p + 1] & 255 : header;
        // Bit 2 is set in a published body-scale packet. Only documented control forms are accepted.
        if (bodyScale && (flags & ~0xe6) != 0) return null;
        int raw = little(data, p + (bodyScale ? 11 : 1));
        boolean pounds = unit == 3;
        String unitLabel = pounds ? "lb" : "kg", step = pounds ? "0.01" : "0.005";
        String protocol = bodyScale ? XIAOMI_V2 : XIAOMI_V1;
        String model = bodyScale ? "Mi Body Composition Scale" : "Mi Smart Scale";
        if ((flags & 0x80) != 0 || raw == 0) {
            return new Reading(protocol, model, false, false, unitLabel, step, null, null, null, null);
        }
        // The maximum published load is 150 kg. Allow rounding in the last imperial increment.
        if (raw > (pounds ? 33070 : 30000)) return null;
        boolean stable = (flags & 0x20) != 0;
        BigDecimal value = pounds ? BigDecimal.valueOf(raw, 2) : BigDecimal.valueOf(raw * 5L, 3);
        String key = stable ? clockToken(data, p + (bodyScale ? 2 : 3)) : null;
        Integer impedance = bodyScale && (flags & 2) != 0 ? little(data, p + 9) : null;
        Double ohms = null;
        if (stable && impedance != null && impedance > 0 && impedance < 3000) {
            ohms = impedance.doubleValue();
        }
        return new Reading(protocol, model, stable, !stable, unitLabel, step, value, key, impedance, ohms);
    }

    /** Date as an opaque replay token, never an inferred instant. Unset or impossible clocks give no token. */
    private static String clockToken(byte[] data, int p) {
        int year = little(data, p), month = data[p + 2] & 255, day = data[p + 3] & 255;
        int hour = data[p + 4] & 255, minute = data[p + 5] & 255, second = data[p + 6] & 255;
        if (year < 2000 || year > 2099 || month < 1 || month > 12 || day < 1
                || hour >= 24 || minute >= 60 || second >= 60) return null;
        int maxDay = 31;
        if (month == 2) maxDay = year % 4 == 0 ? 29 : 28;
        if (month == 4 || month == 6 || month == 9 || month == 11) maxDay = 30;
        if (day > maxDay) return null;
        return year + "-" + month + "-" + day + "T" + hour + "-" + minute + "-" + second;
    }

    private static Reading chipsea(byte[] data, int p, String name, String address) {
        int flags = data[p + 8] & 255;
        if (flags != 0x24 && flags != 0x25) return null;
        boolean publishedProduct = data[p + 6] == 0x0a && data[p + 7] == 1
                && (data[p + 9] & 255) == 0xb4 && data[p + 10] == 0x56 && data[p + 11] == 0x5d;
        boolean knownRebrand = "Yoda0".equalsIgnoreCase(name) || "Yoda1".equalsIgnoreCase(name)
                || "OMIYA-C39-HW".equalsIgnoreCase(name) || "Chipsea-BLE".equalsIgnoreCase(name)
                || "ADV".equalsIgnoreCase(name);
        boolean addressMatches = sameAddress(data, p + 9, address, false);
        if (publishedProduct) {
            if (address != null && !addressMatches) return null;
        } else if (!knownRebrand || !addressMatches) {
            return null;
        }
        String protocol = publishedProduct ? OKOK_C0 : OKOK_C0_MAC;
        int raw = big(data, p + 2), z = big(data, p + 4);
        if (raw == 0xffff) return null;
        boolean stable = (flags & 1) == 1 && raw != 0;
        BigDecimal value = raw == 0 ? null : BigDecimal.valueOf(raw, 2);
        return new Reading(protocol, "Chipsea OKOK C0", stable, !stable && value != null, "kg",
                "0.01", value, String.valueOf(data[p + 1] & 255), z, null);
    }

    private static Reading icomon(byte[] data, int p, String name) {
        if (!"AAA002".equals(name) && !"AAA007".equals(name)
                && !"AAA013".equals(name) && !"AAA07A".equals(name)) return null;
        int mask = data[p + 1] & 255;
        // The manufacturer-ID high octet masks six data bytes after the echoed address.
        byte[] decoded = new byte[6];
        int sum = 0;
        for (int i = 0; i < decoded.length; i++) {
            decoded[i] = (byte) (data[p + 8 + i] ^ mask);
            if (i < 5) sum += decoded[i] & 255;
        }
        if ((sum & 31) != (decoded[5] & 31)) return null;
        int grams = ((decoded[1] & 3) << 16) | big(decoded, 2);
        BigDecimal value = grams == 0 ? null : BigDecimal.valueOf(grams, 3);
        boolean stable = grams > 0 && (decoded[0] & 0x80) != 0;
        return new Reading(ICOMON_AAA, "Icomon " + name, stable, !stable && grams > 0,
                "kg", "0.001", value, null, null, null);
    }

    private static boolean sameAddress(byte[] data, int p, String address, boolean reversed) {
        if (address == null || address.length() != 17) return false;
        boolean allZero = true, allFF = true;
        for (int i = 0; i < 6; i++) {
            int j = 3 * i;
            if (i > 0 && address.charAt(j - 1) != ':') return false;
            int hi = Character.digit(address.charAt(j), 16), lo = Character.digit(address.charAt(j + 1), 16);
            int octet = data[p + (reversed ? 5 - i : i)] & 255;
            if (hi < 0 || lo < 0 || (hi * 16 + lo) != octet) return false;
            allZero &= octet == 0;
            allFF &= octet == 255;
        }
        return !allZero && !allFF;
    }

    private static int big(byte[] b, int p) { return ((b[p] & 255) << 8) | (b[p + 1] & 255); }
    private static int little(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }
}
