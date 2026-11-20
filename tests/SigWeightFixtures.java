package uk.co.violetscale.probe;

import java.time.Instant;
import java.util.Locale;
import java.util.TimeZone;

/** Published SIG wire-format examples and constructed combinations, never user measurements. */
public final class SigWeightFixtures {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC"), LONDON = TimeZone.getTimeZone("Europe/London");
    private static final String TIME = "EA070714100A39"; // 20 July 2026, 16:10:57 local
    private static int checks;

    public static void main(String[] args) {
        for (int flags = 0; flags < 16; flags++) {
            String value = String.format(Locale.UK, "%02X803E", flags) + ((flags & 2) != 0 ? TIME : "")
                    + ((flags & 4) != 0 ? "0A" : "") + ((flags & 8) != 0 ? "E800D606" : "");
            SigWeight.Reading r = decode(value, UTC);
            check(r != null);
            boolean pounds = (flags & 1) != 0;
            check((pounds ? "160" : "80").equals(r.originalValue));
            check((pounds ? "lb" : "kg").equals(r.originalUnit));
            check((pounds ? "0.01" : "0.005").equals(r.originalResolution));
            check(Math.abs(r.kg - (pounds ? 72.5747792 : 80)) < 0.0000001);
            check((flags & 4) != 0 ? Integer.valueOf(10).equals(r.userId) : r.userId == null);
            check((flags & 2) != 0 ? r.deviceTimeMs == Instant.parse("2026-07-20T16:10:57Z").toEpochMilli() : r.deviceTimeMs == null);
            check(r.sampleKey.isEmpty() == ((flags & 2) == 0));
            check(decode(value + "00", UTC) == null);
            check(decode(value.substring(0, value.length() - 2), UTC) == null);
        }
        check("0.005".equals(decode("000100", UTC).originalValue));
        check("0.01".equals(decode("010100", UTC).originalValue));
        check(Integer.valueOf(255).equals(decode("040100FF", UTC).userId));
        check(decode("000000", UTC) == null);
        check(decode("01FFFF", UTC) == null);
        check(decode("10803E", UTC) == null);
        check(decode("", UTC) == null);
        check(SigWeight.decode(null, UTC) == null);
        for (String invalid : new String[]{"00000714100A39", "EA070014100A39", "EA070700100A39",
                "EA07021D100A39", "EA070714180A39", "EA070714103C39", "EA070714100A3C"}) {
            check(decode("02803E" + invalid, UTC) == null);
        }
        SigWeight.Reading local = decode("02803E" + TIME, LONDON);
        check(local.deviceTimeMs == Instant.parse("2026-07-20T15:10:57Z").toEpochMilli());
        check(local.sampleKey.equals(decode("02803E" + TIME, UTC).sampleKey));
        check(local.sampleKey.equals(decode("0A803E" + TIME + "00000000", UTC).sampleKey));
        check(!local.sampleKey.equals(decode("06803E" + TIME + "00", UTC).sampleKey));
        check(!local.sampleKey.equals(decode("02813E" + TIME, UTC).sampleKey));
        check(!local.sampleKey.equals(decode("03803E" + TIME, UTC).sampleKey));
        check(decode("02803EE807021D100A39", UTC) != null); // Leap day in 2024
        check(decode("02803EEA07031D011E00", LONDON) == null); // UK spring-forward gap
        System.out.println(checks + " standard GATT weight fixture checks passed");
    }

    private static SigWeight.Reading decode(String hex, TimeZone timezone) {
        return SigWeight.decode(Decoder.fromHex(hex), timezone);
    }
    private static void check(boolean pass) {
        if (!pass) throw new AssertionError("Standard weight check " + (checks + 1));
        checks++;
    }
}
