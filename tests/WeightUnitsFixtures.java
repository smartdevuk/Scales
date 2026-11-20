package uk.co.violetscale.probe;

public final class WeightUnitsFixtures {
    public static void main(String[] args) {
        final double pound = 0.45359237;
        equal("63.5", WeightUnits.amount(63.5, "kg"));
        equal("140", WeightUnits.amount(63.5, "lb"));
        equal("10 st 0", WeightUnits.amount(63.5, "st"));
        equal("10 st 13.9", WeightUnits.amount(153.94*pound, "st"));
        equal("11 st 0", WeightUnits.amount(153.96*pound, "st"));
        close(10.5*pound, WeightUnits.toKg("st", null, 10.5));
        close(140*pound, WeightUnits.toKg("st", 10d, null));
        close(143.5*pound, WeightUnits.toKg("st", 10d, 3.5));
        close(167.8*pound, WeightUnits.toKg("lb", null, 167.8));
        equal(null, WeightUnits.toKg("kg", null, null));
        equal(-2L, WeightUnits.changeTenths(-0.16, "kg"));
        equal(0L, WeightUnits.changeTenths(-0.04, "kg"));
        equal(0L, WeightUnits.changeTenths(0.02, "lb"));
        equal(-4L, WeightUnits.changeTenths(-0.4*pound, "st"));
        equal(4L, WeightUnits.changeTenths(0.4*pound, "lb"));
        equal("0.0 kg", WeightUnits.changeMagnitude(0,"kg"));
        equal("0.4 lb", WeightUnits.changeMagnitude(-4,"st"));
        for (double[] invalid : new double[][]{{1.5, 0}, {1, 14}, {1, 14.1}}) {
            try {
                WeightUnits.toKg("st", invalid[0], invalid[1]);
                throw new AssertionError("Accepted invalid stone/pound components");
            } catch (IllegalArgumentException expected) {}
        }
        System.out.println("WeightUnitsFixtures passed");
    }
    private static void equal(Object expected,Object actual) {
        if(!java.util.Objects.equals(expected,actual))throw new AssertionError("Expected "+expected+", got "+actual);
    }
    private static void close(double expected,double actual) {
        if(Math.abs(expected-actual)>.0000001)throw new AssertionError("Expected "+expected+", got "+actual);
    }
}
