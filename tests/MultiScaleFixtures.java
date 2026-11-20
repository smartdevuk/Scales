package uk.co.violetscale.probe;

import java.util.TimeZone;

/** Synthetic routes, session transitions and persistence inputs exercised by Today. */
public final class MultiScaleFixtures {
    private static int checks;
    private static final String ADDRESS = "A2:11:22:33:44:55";
    private static final long RECEIVED = 1784116800000L;

    public static void main(String[] args) {
        String first = "10FFC0FE20990BAD0000250000000000000609596F646130";
        ok(ScaleRoute.YODA.equals(route(first, "Yoda0", false, false, null).protocol));
        ScaleReading yoda = ScaleReading.yoda(WeighFrame.parse(Decoder.fromHex(first)), RECEIVED);
        ok(yoda.impedanceOhms == null && yoda.kg == 83.45 && "83.45".equals(yoda.originalValue));
        ok(yoda.measuredAt == RECEIVED && yoda.receivedAt == RECEIVED && "yoda0-1".equals(yoda.decoder));
        ok(WeighFrame.parse(Decoder.fromHex(first + "0001")) == null);

        String v1 = "0D161D18231027EA070B14081605";
        String v2 = "10161B180226EA070B140816055802983A";
        ScaleRoute sig = route("03031D18", "Personal scale", true, true, null);
        ok(sig.gatt() && ScaleRoute.SIG.equals(sig.protocol));
        ok(route("03031D18", null, true, true, null).gatt());
        ok(route("03031D18", "Non-connectable beacon", true, false, null) == null);
        ScaleRoute unknown = route("020106", "Desk scale", false, true, null);
        ok(ScaleRoute.UNVERIFIED.equals(unknown.protocol));
        ok(ScaleRoute.SIG.equals(route("03031D18", "Desk scale", true, true, unknown).protocol));
        ok(route("020106", null, false, true, null) == null);
        ScaleRoute x = route(v1, "MI_SCALE", true, true, sig);
        ok(ScaleBroadcast.XIAOMI_V1.equals(x.protocol) && !x.gatt());
        ok(x == route("020106", "MI_SCALE", true, true, x)); // later scan response omits load
        ok(ScaleBroadcast.XIAOMI_V2.equals(route(v2, "MIBFS", false, false, null).protocol));
        ok(route(v2, "Lamp", false, false, null) == null);
        String manufacturer = "10FFC03416220568000025A21122334455";
        ok(ScaleBroadcast.OKOK_C0_MAC.equals(route(manufacturer, "Yoda1", false, false, null).protocol));
        ok(route(manufacturer, "Lamp", false, false, null) == null);
        ok(ScaleRoute.identify(Decoder.fromHex(manufacturer), "Yoda1", "A2:11:22:33:44:56", false, false, null) == null);
        ScaleRoute published = ScaleRoute.identify(Decoder.fromHex("10FFC007185A00500A0125B4565D001122"),
                null, "B4:56:5D:00:11:22", false, false, null);
        ok(ScaleBroadcast.OKOK_C0.equals(published.protocol));

        ScaleBroadcast.Reading live = ScaleBroadcast.decode(Decoder.fromHex(v1.replace("1823", "1803")), "MI_SCALE", ADDRESS);
        ScaleBroadcast.Reading result = ScaleBroadcast.decode(Decoder.fromHex(v1), "MI_SCALE", ADDRESS);
        WeighSession.BroadcastCycle cycle = new WeighSession.BroadcastCycle();
        ok(live.start && cycle.observeLive(live.sessionKey, 3000));
        ok(result.stable && cycle.finish(result.sessionKey,"mi/100/lb",4000) == WeighSession.Signal.READY);
        ok(cycle.finish(result.sessionKey,"mi/100/lb",4100) == WeighSession.Signal.IGNORE);
        ScaleReading saved = ScaleReading.broadcast(result, RECEIVED);
        ok(Math.abs(saved.kg - 45.359237) < 0.0000001 && "100".equals(saved.originalValue));
        ok("lb".equals(saved.originalUnit) && "0.01".equals(saved.originalResolution));
        ok(saved.measuredAt == RECEIVED && saved.impedanceOhms == null && saved.decoder.equals(x.protocol+"-1"));

        cycle = new WeighSession.BroadcastCycle();
        ok(cycle.finish("old","old/lb",2000) == WeighSession.Signal.CHECK); // final-only at launch
        cycle.observeLive(null, 3000);
        cycle.clear(3500); // stepped off before a new final
        ok(cycle.finish(null,"new/lb",3700) == WeighSession.Signal.CHECK);
        cycle.observeLive("11", 4000);
        ok(cycle.finish("12","12/lb",4300) == WeighSession.Signal.CHECK); // counter differs
        cycle.observeLive(null, 5000);
        ok(!cycle.observeLive(null, 4999));
        ok(cycle.finish(null,"late/lb",16000) == WeighSession.Signal.CHECK);
        cycle.observeLive(null, 17000);
        ok(cycle.finish(null,"late/lb",17500) == WeighSession.Signal.READY); // deliberate identical new step-on
        ok(cycle.started() == 17000);

        ScaleBroadcast.Reading impedance = ScaleBroadcast.decode(Decoder.fromHex(v2), "MIBFS", ADDRESS);
        ScaleBroadcast.Reading before = ScaleBroadcast.decode(Decoder.fromHex(v2.replace("0226", "0224")), "MIBFS", ADDRESS);
        ok(before.impedanceOhms == null && impedance.sessionKey.equals(before.sessionKey));
        ok(impedance.originalValue.equals(before.originalValue));
        ok(ScaleReading.broadcast(impedance, RECEIVED).impedanceOhms == 600.0);

        SigWeight.Reading standard = SigWeight.decode(Decoder.fromHex("010027"), TimeZone.getTimeZone("UTC"));
        ScaleReading sigReading = ScaleReading.standard(standard, sig.model, RECEIVED);
        ok("lb".equals(sigReading.originalUnit) && "99.84".equals(sigReading.originalValue));
        ok(sigReading.measuredAt == RECEIVED && sigReading.receivedAt == RECEIVED);
        ok(!WeighSession.freshDeviceClock(standard.deviceTimeMs, RECEIVED)); // explicit confirmation
        ok(WeighSession.freshDeviceClock(RECEIVED - 20_000, RECEIVED));
        ok(!WeighSession.freshDeviceClock(RECEIVED - 60_000, RECEIVED));
        ok(!WeighSession.plausibleDeviceClock(RECEIVED + 120_000, RECEIVED));
        ok(!WeighSession.plausibleDeviceClock(1L, RECEIVED));
        System.out.println(checks + " multi-scale routing, session and persistence metadata checks passed");
    }

    private static ScaleRoute route(String hex, String name, boolean service, boolean connectable, ScaleRoute old) {
        return ScaleRoute.identify(Decoder.fromHex(hex), name, ADDRESS, service, connectable, old);
    }
    private static void ok(boolean result) {
        if (!result) throw new AssertionError("Check " + (checks + 1) + " failed");
        checks++;
    }
}
