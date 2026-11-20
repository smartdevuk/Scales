package uk.co.violetscale.probe;

/** Runs on an ordinary JVM using android.jar on the classpath. No BLE radio needed. */
public final class DecoderFixtures {
    private static int checks;

    public static void main(String[] args) {
        final String example = "10FFC03516D017700A0125B4565D383042";
        final String exampleResult = "OKOK candidate · 58.40 kg · 600.0 Ω";
        check(exampleResult, Decoder.advertisement(example));
        check(exampleResult, Decoder.advertisement("020106" + example + "00"));
        check(exampleResult, Decoder.advertisement(example.replace("C035", "C03A")));
        check(exampleResult, Decoder.advertisement(example.replace("0125", "0124")));
        check("OKOK candidate · 58.40 kg", Decoder.advertisement(example.replace("1770", "0000")));
        check(null, Decoder.advertisement(example.substring(0, example.length() - 2)));
        check(null, Decoder.advertisement(example.replace("10FF", "1009")));
        check(null, Decoder.advertisement(example.replace("C035", "CA35")));
        check(null, Decoder.advertisement(example.replace("0A01", "0B01")));
        check(null, Decoder.advertisement(example.replace("0125", "0135")));
        check(null, Decoder.advertisement(example.replace("B4565D", "000000")));
        check(null, Decoder.advertisement(example.replace("16D0", "0000")));
        check(null, Decoder.advertisement(example + "0EFF"));
        check(null, Decoder.advertisement(example + example.replace("16D0", "16D1")));
        check(null, Decoder.advertisement("C03516D017700A0125B4565D383042"));
        check(null, Decoder.advertisement("10ffzz"));
        check("Bluetooth weight · 70.005 kg", Decoder.weightMeasurement("00b136"));
        check("Bluetooth weight · 155.5 lb", Decoder.weightMeasurement("01be3c"));
        check("Bluetooth weight · 70 kg", Decoder.weightMeasurement("0EB036EA070B14100A39031701A406"));
        check(null, Decoder.weightMeasurement("04b136")); // Missing user ID.
        check(null, Decoder.weightMeasurement("00b13601"));
        check(null, Decoder.weightMeasurement("10b136"));
        check(null, Decoder.weightMeasurement("00ffff"));
        check(null, Decoder.weightMeasurement("000000"));
        check(null, Decoder.weightMeasurement("01f"));
        check(null, Decoder.weightMeasurement(null));
        System.out.println(checks + " decoder fixture checks passed");
    }

    private static void check(String expected, String actual) {
        ++checks;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Check " + checks + ": expected " + expected + ", got " + actual);
        }
    }
}
