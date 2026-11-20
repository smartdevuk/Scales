package uk.co.violetscale.probe;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Convert at the display boundary. All records retain the original kilogram value. */
final class WeightUnits {
    private static final double KG_PER_LB = 0.45359237;
    private WeightUnits() {}

    static long tenthsOfPound(double kg) {
        return Math.round(kg / KG_PER_LB * 10);
    }
    static long changeTenths(double deltaKg, String unit) {
        double magnitude = Math.abs(deltaKg);
        long tenths = "kg".equals(unit) ? Math.round(magnitude * 10) : tenthsOfPound(magnitude);
        return deltaKg < 0 ? -tenths : tenths;
    }
    static String changeMagnitude(long signedTenths, String unit) {
        return BigDecimal.valueOf(Math.abs(signedTenths), 1).toPlainString() + ("kg".equals(unit) ? " kg" : " lb");
    }

    static String amount(double kg, String unit) {
        if ("kg".equals(unit)) return BigDecimal.valueOf(kg).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        long tenths = tenthsOfPound(kg);
        if ("lb".equals(unit)) return decimalPounds(tenths);
        return tenths / 140 + " st " + decimalPounds(tenths % 140);
    }

    private static String decimalPounds(long tenths) {
        return BigDecimal.valueOf(tenths, 1).stripTrailingZeros().toPlainString();
    }

    // For "st", value is the remaining pounds. Either box may be left blank.
    static Double toKg(String unit, Double stones, Double value) {
        if (value == null && stones == null) return null;
        if ("st".equals(unit)) {
            if (stones != null && stones != Math.rint(stones))
                throw new IllegalArgumentException("Enter whole stones and the remaining pounds.");
            if (value != null && value >= 14)
                throw new IllegalArgumentException("Remaining pounds must be less than 14.");
            return ((stones == null ? 0 : stones) * 14 + (value == null ? 0 : value)) * KG_PER_LB;
        }
        return "lb".equals(unit) ? value * KG_PER_LB : value;
    }
}
