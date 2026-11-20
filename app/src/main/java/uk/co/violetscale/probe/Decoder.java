package uk.co.violetscale.probe;

import org.json.JSONObject;

import java.util.Locale;
import java.util.TimeZone;

public final class Decoder {
    public static final String VERSION = "yoda0-1";
    private static final String WEIGHT_SERVICE = "0000181d-0000-1000-8000-00805f9b34fb";
    private static final String WEIGHT_MEASUREMENT = "00002a9d-0000-1000-8000-00805f9b34fb";

    private Decoder() {}

    public static String describe(JSONObject event) {
        if (event == null) return null;
        if ("advertisement".equals(event.optString("kind"))) {
            return advertisement(event.optString("rawHex"));
        }
        String type = event.optString("type");
        if (("gatt_read".equals(type) || "gatt_notification".equals(type))
                && WEIGHT_SERVICE.equalsIgnoreCase(event.optString("serviceUuid"))
                && WEIGHT_MEASUREMENT.equalsIgnoreCase(event.optString("characteristicUuid"))) {
            return weightMeasurement(event.optString("valueHex"));
        }
        return null;
    }

    // Bluetooth SIG Weight Scale Service 1.0.1, section 3.2.
    static String weightMeasurement(String hex) {
        SigWeight.Reading reading = SigWeight.decode(fromHex(hex), TimeZone.getDefault());
        return reading == null ? null : "Bluetooth weight · " + reading.originalValue + " " + reading.originalUnit;
    }

    static String advertisement(String hex) {
        return advertisementBytes(fromHex(hex));
    }

    static String advertisementBytes(byte[] record) {
        if (record == null) return null;
        WeighFrame frame = WeighFrame.parse(record);
        if (frame != null) {
            if (frame.centikg == 0) return "Yoda0 · Starting a weigh-in";
            return String.format(Locale.UK, "Yoda0 · %.2f kg", frame.centikg / 100.0);
        }
        String decoded = null;
        int pos = 0;
        while (pos < record.length) {
            int length = record[pos++] & 0xff;
            if (length == 0) break; // Android may zero-pad a scan record.
            if (length > record.length - pos) return null;
            if ((record[pos] & 0xff) == 0xff && length == 16) {
                String candidate = publishedC0Frame(record, pos + 1);
                if (candidate != null) {
                    if (decoded != null && !decoded.equals(candidate)) return null;
                    decoded = candidate;
                }
            }
            pos += length;
        }
        return decoded;
    }

    // The BodyScale hardware report documents this specific product, OUI and resolution.
    // Use the complete manufacturer AD value, including the two bytes Android
    // would ordinarily turn into a separate little-endian company identifier.
    private static String publishedC0Frame(byte[] bytes, int start) {
        if ((bytes[start] & 0xff) != 0xc0
                || bytes[start + 6] != 0x0a || bytes[start + 7] != 0x01
                || (bytes[start + 8] & 0xfe) != 0x24
                || (bytes[start + 9] & 0xff) != 0xb4 || bytes[start + 10] != 0x56
                || bytes[start + 11] != 0x5d) return null;
        int weight = ((bytes[start + 2] & 0xff) << 8) | (bytes[start + 3] & 0xff);
        if (weight == 0 || weight == 0xffff) return null;
        int impedance = ((bytes[start + 4] & 0xff) << 8) | (bytes[start + 5] & 0xff);
        String value = String.format(Locale.UK, "OKOK candidate · %.2f kg", weight / 100.0);
        if (impedance != 0 && impedance != 0xffff) {
            value += String.format(Locale.UK, " · %.1f Ω", impedance / 10.0);
        }
        return value;
    }

    static byte[] fromHex(String hex) {
        if (hex == null || (hex.length() & 1) != 0) return null;
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(hex.charAt(2 * i), 16);
            int low = Character.digit(hex.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) return null;
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
