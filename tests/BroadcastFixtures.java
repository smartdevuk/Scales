package uk.co.violetscale.probe;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/** Published measurement vectors plus deliberately synthetic scanner records and negative cases. */
public final class BroadcastFixtures {
    private static int checks;

    public static void main(String[] args) {
        miWeightScale();
        miBodyScale();
        chipsea();
        icomon();
        malformed();
        System.out.println(checks + " broadcast decoder checks passed");
    }

    private static void miWeightScale() {
        // openScale wiki's captured measurement-layout example, placed in a service-data envelope.
        String published = "0D161D1862AC49E0070C140D1C04";
        ScaleBroadcast.Reading r = read(published, "MI_SCALE");
        eq(ScaleBroadcast.XIAOMI_V1, r.protocol);
        eq(94.3d, r.kg);
        eq("94.3", r.originalValue);
        eq("0.005", r.originalResolution);
        eq(true, r.stable);
        eq(false, r.start);
        eq("2016-12-20T13-28-4", r.sessionKey);
        eq(null, r.measuredAtMs);
        eq(null, r.impedanceOhms);
        eq(r.sessionKey, read(published, "MI_SCALE").sessionKey);
        eq(null, read(published, "MIBFS"));
        eq(null, read(published, "Kitchen scale"));
        eq(null, read(published, null));
        eq(null, read(published + name("OTHER"), "MI_SCALE"));
        eq(94.3d, read(published + name("MI_SCALE"), null).kg);
        eq(94.3d, read(name("MI SCALE2") + published, null).kg);
        r = read(published.replace("1862", "1802"), "MI_SCALE");
        eq(false, r.stable);
        eq(true, r.start);
        eq(null, r.sessionKey);
        r = read(published.replace("1862", "18A2"), "MI_SCALE");
        eq(null, r.kg);
        eq(false, r.stable);
        eq(false, r.start);
        eq(null, read(published.replace("1862", "1832"), "MI_SCALE")); // Unverified local jin factor.
        r = read("0D161D18231027EA070B14081605", "MI_SCALE");
        eq(45.359237d, r.kg);
        eq("lb", r.originalUnit);
        eq("100", r.originalValue);
        eq("0.01", r.originalResolution);
        eq(null, read(published.replace("AC49", "FFFF"), "MI_SCALE"));
        eq(null, read(published.replace("0C14", "0230"), "MI_SCALE").sessionKey);
        eq("2016-2-29T13-28-4", read(published.replace("0C14", "021D"), "MI_SCALE").sessionKey);
        eq(null, read(published.replace("E0070C14", "DF07021D"), "MI_SCALE").sessionKey);
        eq(null, read(published.replace("AC49", "0000"), "MI_SCALE").kg);
    }

    private static void miBodyScale() {
        // ipanalytics' published 75 kg completed and subsequent load-removed frames.
        String captured = "10161B180224B2070104030637FDFF983A";
        ScaleBroadcast.Reading r = read(captured, "MIBFS");
        eq(ScaleBroadcast.XIAOMI_V2, r.protocol);
        eq(75.0d, r.kg);
        eq("kg", r.originalUnit);
        eq(true, r.stable);
        eq(false, r.start);
        eq(null, r.sessionKey); // Unset clock (1970).
        eq(null, r.impedanceRaw);
        eq(null, r.impedanceOhms);
        eq(75.0d, read(captured + name("MIBCS"), null).kg);
        eq(75.0d, read(captured + name("MI SCALE2"), null).kg);
        eq(null, read(captured + name("MI_SCALE"), null));
        r = read("10161B180284B207010403072600001400", "MIBFS");
        eq(null, r.kg);
        eq(false, r.start);
        eq(false, r.stable);
        r = read(captured.replace("0224", "0226").replace("FDFF", "5802"), "MIBFS");
        eq(Integer.valueOf(600), r.impedanceRaw);
        eq(600.0d, r.impedanceOhms);
        r = read(captured.replace("0224", "0226"), "MIBFS");
        eq(null, r.impedanceOhms);
        eq(75.0d, r.kg);
        r = read(captured.replace("0224", "0206").replace("FDFF", "5802"), "MIBFS");
        eq(true, r.start);
        eq(false, r.stable);
        eq(null, r.impedanceOhms);
        r = read(captured.replace("0224", "0324").replace("983A", "1027"), "MIBFS");
        eq(45.359237d, r.kg);
        eq("lb", r.originalUnit);
        eq(null, read(captured.replace("0224", "1224"), "MIBFS"));
        eq(null, read(captured.replace("0224", "022C"), "MIBFS"));
    }

    private static void chipsea() {
        // BodyScale's documented non-connectable C0 example (published 58.40 kg, raw Z 6000).
        String example = "10FFC03516D017700A0125B4565D383042";
        String own = "B4:56:5D:38:30:42";
        ScaleBroadcast.Reading r = ScaleBroadcast.decode(hex(example), null, own);
        eq(ScaleBroadcast.OKOK_C0, r.protocol);
        eq(58.4d, r.kg);
        eq("53", r.sessionKey);
        eq(true, r.stable);
        eq(false, r.start);
        eq(6000, r.impedanceRaw);
        eq(null, r.impedanceOhms);
        eq(null, ScaleBroadcast.decode(hex(example), "Yoda0", "00:11:22:33:44:55"));
        eq(null, read(example.replace("0A01", "0A02"), null));
        eq(null, read(example.replace("0125", "01A5"), null));
        r = read(example.replace("0125", "0124"), null);
        eq(true, r.start);
        eq(false, r.stable);
        // LEDINUO Yoda0 field report, 85.00 kg and matching advertised address.
        String macFixture = "10FFC01421341388000025E0D38E450638";
        String mac = "e0:d3:8e:45:06:38";
        r = ScaleBroadcast.decode(hex(macFixture), "Yoda0", mac);
        eq(ScaleBroadcast.OKOK_C0_MAC, r.protocol);
        eq(85.0d, r.kg);
        eq(5000, r.impedanceRaw);
        eq(null, r.impedanceOhms);
        eq(null, ScaleBroadcast.decode(hex(macFixture), "Unknown device", mac));
        eq(null, ScaleBroadcast.decode(hex(macFixture), "Yoda0", null));
        eq(null, ScaleBroadcast.decode(hex(macFixture), "Yoda0", "e0:d3:8e:45:06:39"));
        eq(null, ScaleBroadcast.decode(hex(macFixture), "Yoda0", "e0xd3:8e:45:06:38"));
        String zeroAddress = "10FFC03413880000000025000000000000";
        eq(null, ScaleBroadcast.decode(hex(zeroAddress), "Yoda0", "00:00:00:00:00:00"));
        eq(null, read(example.replace("16D0", "FFFF"), null));
        eq(null, read(example.replace("16D0", "0000"), null).kg);
        r = read(example.replace("16D0", "0000").replace("0125", "0124"), null);
        eq(false, r.start); // No evidence that a zero frame means a new load on this product.
        eq(false, r.stable);
    }

    private static void icomon() {
        // Constructed AAA-series packet, 72,500 g, mask 0x39 and reversed advertiser address.
        String address = "12:34:56:78:9A:BC";
        String prefix = "0FFF1039BC9A78563412";
        String finalWeight = prefix + "B938220D9424";
        for (String name : new String[]{"AAA002", "AAA007", "AAA013", "AAA07A"}) {
            ScaleBroadcast.Reading r = ScaleBroadcast.decode(hex(finalWeight), name, address);
            eq(ScaleBroadcast.ICOMON_AAA, r.protocol);
            eq(72.5d, r.kg);
            eq("0.001", r.originalResolution);
            eq(true, r.stable);
            eq(false, r.start);
            eq(null, r.sessionKey);
            eq(null, r.impedanceOhms);
        }
        ScaleBroadcast.Reading live = ScaleBroadcast.decode(hex(prefix + "3938220D9424"), "AAA002", address);
        eq(true, live.start);
        eq(false, live.stable);
        eq(72.5d, live.kg);
        eq(null, ScaleBroadcast.decode(hex(finalWeight), "Fitdays", address));
        eq(null, ScaleBroadcast.decode(hex(finalWeight), "AAA002", null));
        eq(null, ScaleBroadcast.decode(hex(finalWeight), "AAA002", "BC:9A:78:56:34:12"));
        eq(null, ScaleBroadcast.decode(hex(prefix + "B938220D9425"), "AAA002", address)); // checksum
        eq(null, ScaleBroadcast.decode(hex(prefix + "B938220D9F1D"), "AAA002", address)); // impedance type
        eq(null, ScaleBroadcast.decode(hex("0EFF1039BC9A78563412B938220D94"), "AAA002", address));
        ScaleBroadcast.Reading empty = ScaleBroadcast.decode(hex(prefix + "B93939399434"), "AAA002", address);
        eq(null, empty.kg);
        eq(false, empty.stable);
        eq(false, empty.start);
        eq(72.55d, ScaleBroadcast.decode(hex(prefix + "B938225F9436" + name("AAA007")), null, address).kg);
    }

    private static void malformed() {
        String v2 = "10161B180224B2070104030637FDFF983A";
        byte[] complete = hex(v2);
        for (int i = 0; i < complete.length; i++) {
            eq(null, ScaleBroadcast.decode(Arrays.copyOf(complete, i), "MIBFS"));
        }
        eq(null, ScaleBroadcast.decode(null, null));
        eq(null, read("03031B18" + name("MIBFS"), null)); // Service UUID only.
        eq(null, read("02161B", "MIBFS"));
        eq(null, read(v2 + "0EFF", "MIBFS"));
        eq(null, read(v2 + "0012", "MIBFS"));
        eq(null, read(v2 + v2, "MIBFS"));
        eq(null, read(v2 + name("MIBFS") + name("other"), null));
        eq(null, read(v2 + "10FFC03516D017700A0125B4565D383042", "MIBFS"));
        eq(75.0d, read("020106" + v2 + "000000", "MIBFS").kg);
        Random random = new Random(117);
        for (int i = 0; i < 1200; i++) {
            byte[] garbage = new byte[random.nextInt(64)];
            random.nextBytes(garbage);
            ScaleBroadcast.decode(garbage, "MI_SCALE");
        }
    }

    private static ScaleBroadcast.Reading read(String record, String name) {
        return ScaleBroadcast.decode(hex(record), name);
    }

    private static String name(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        StringBuilder field = new StringBuilder(String.format("%02x09", bytes.length + 1));
        for (byte b : bytes) field.append(String.format("%02x", b & 255));
        return field.toString();
    }

    private static byte[] hex(String s) {
        byte[] result = new byte[s.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static void eq(Object expected, Object actual) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Check " + checks + " expected " + expected + ", got " + actual);
        }
    }
}
