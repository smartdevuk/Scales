package uk.co.violetscale.probe;

/** Synthetic packets, timings, corruption and boundary cases for the Yoda0 layout. */
public final class WeighFixtures {
    private static final String WAKE = "10FFC0FE000000000000240000000000000609596F646130";
    private static final String FINAL = "10FFC0FE20990BAD0000250000000000000609596F646130";
    private static int checks;

    public static void main(String[] args) {
        WeighFrame zero = read(WAKE);
        WeighFrame weight = read(FINAL);
        ok(zero.centikg == 0 && zero.sequence == 0xfe);
        ok(weight.centikg == 8345 && weight.sequence == 0xfe);
        ok(read(FINAL.replace("C0FE", "C000")).centikg == 8345); // sequence can wrap
        ok(read("020106" + FINAL + "0000").centikg == 8345);
        ok(read(FINAL.replace("2099", "1E5A")).centikg == 7770);
        ok(read(FINAL.replace("0BAD", "0000")).centikg == 8345);
        ok(read(FINAL.replace("0BAD", "FFFF")).centikg == 8345);
        ok(read(FINAL.replace("2099", "FFFF")) == null);
        ok(read(FINAL.replace("2099", "0000")) == null);
        ok(read(FINAL.replace("C0FE", "CAFE")) == null);
        ok(read(FINAL.replace("000025", "000024")) == null);
        ok(read(FINAL.replace("000025", "000035")) == null);
        ok(read(FINAL.replace("000025", "0A0125")) == null);
        ok(read(FINAL.replace("00000609", "00010609")) == null);
        ok(read(FINAL.replace("596F646130", "596F646131")) == null);
        ok(read(FINAL.substring(0, 34)) == null);
        ok(read(FINAL + FINAL) == null);
        ok(read(FINAL + "0A01") == null);
        ok(read(FINAL.substring(0, FINAL.length() - 2)) == null);
        ok(read(WAKE.replace("00000000000024", "00000BAD000024")) == null);
        ok(Decoder.advertisement(FINAL).equals("Yoda0 · 83.45 kg"));

        WeighSession s = new WeighSession();
        expect(s, zero, 100, WeighSession.Signal.STARTING);
        expect(s, weight, 250, WeighSession.Signal.STABILISING);
        expect(s, weight, 350, WeighSession.Signal.STABILISING);
        expect(s, weight, 820, WeighSession.Signal.STABILISING);
        expect(s, weight, 1060, WeighSession.Signal.READY);
        expect(s, weight, 1200, WeighSession.Signal.IGNORE);
        expect(s, weight, 1190, WeighSession.Signal.IGNORE);
        expect(s, zero, 1500, WeighSession.Signal.IGNORE);
        expect(s, read(WAKE.replace("C0FE", "C0FF")), 1700, WeighSession.Signal.STARTING);
        WeighFrame second = read(FINAL.replace("C0FE", "C0FF"));
        expect(s, second, 1900, WeighSession.Signal.STABILISING);
        expect(s, second, 2300, WeighSession.Signal.STABILISING);
        expect(s, second, 2750, WeighSession.Signal.READY); // deliberate identical repeat

        s = new WeighSession();
        expect(s, weight, 110, WeighSession.Signal.STABILISING);
        expect(s, weight, 500, WeighSession.Signal.STABILISING);
        expect(s, weight, 950, WeighSession.Signal.CHECK); // no start captured
        WeighFrame changed = read(FINAL.replace("2099", "20AF"));
        expect(s, changed, 1100, WeighSession.Signal.STABILISING);
        expect(s, changed, 1400, WeighSession.Signal.STABILISING);
        expect(s, changed, 2000, WeighSession.Signal.CHECK);

        s = new WeighSession();
        expect(s, zero, 100, WeighSession.Signal.STARTING);
        expect(s, weight, 26000, WeighSession.Signal.STABILISING);
        expect(s, weight, 26400, WeighSession.Signal.STABILISING);
        expect(s, weight, 26900, WeighSession.Signal.CHECK); // expired start
        s = new WeighSession();
        expect(s, zero, 100, WeighSession.Signal.STARTING);
        expect(s, weight, 200, WeighSession.Signal.STABILISING);
        expect(s, weight, 1900, WeighSession.Signal.STABILISING); // silence interrupts quorum
        expect(s, weight, 2150, WeighSession.Signal.STABILISING);
        expect(s, weight, 2800, WeighSession.Signal.READY);
        ok(WeighSession.needsOwnerCheck(null, 83.45));
        ok(WeighSession.needsOwnerCheck(83.45, 68));
        ok(!WeighSession.needsOwnerCheck(83.45, 83.1));
        ok(WeighSession.repeats("a/254/8345", 15000, "a/254/8345", 12000));
        ok(!WeighSession.repeats("a/255/8345", 15000, "a/254/8345", 12000));
        ok(!WeighSession.repeats("b/254/8345", 15000, "a/254/8345", 12000));
        ok(!WeighSession.repeats("a/254/8345", 250000, "a/254/8345", 12000));
        System.out.println(checks + " weighing fixture checks passed");
    }

    private static WeighFrame read(String frame) { return WeighFrame.parse(Decoder.fromHex(frame)); }
    private static void expect(WeighSession session, WeighFrame frame, long time, WeighSession.Signal result) {
        ok(session.accept(frame, time) == result);
    }
    private static void ok(boolean truth) {
        if (!truth) throw new AssertionError("Check " + (checks+1) + " failed");
        checks++;
    }
}
