package uk.co.violetscale.probe;

/** Optional goal selected by the user, expressed in the canonical measurement unit. */
final class TrendGoal {
    final double lowKg, highKg;
    final boolean range;

    private TrendGoal(double low, double high, boolean range) {
        lowKg = low;
        highKg = high;
        this.range = range;
    }

    static TrendGoal read(String mode, String target, String low, String high) {
        try {
            if ("maintain".equals(mode)) {
                double lower = Double.parseDouble(low), upper = Double.parseDouble(high);
                if (lower > 0 && upper <= 1000 && lower < upper) return new TrendGoal(lower, upper, true);
            } else if ("gain".equals(mode) || "lose".equals(mode)) {
                double goal = Double.parseDouble(target);
                if (goal > 0 && goal <= 1000) return new TrendGoal(goal, goal, false);
            }
        } catch (NumberFormatException emptyOrMalformed) {
            // An enabled goal can deliberately have no target.
        }
        return null;
    }

    /** Signed distance to the closest boundary. Both endpoints belong to a maintenance range. */
    double distanceKg(double kg) { return kg < lowKg ? kg - lowKg : kg > highKg ? kg - highKg : 0; }
}
