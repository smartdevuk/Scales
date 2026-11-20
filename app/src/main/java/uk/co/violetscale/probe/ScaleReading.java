package uk.co.violetscale.probe;

import java.math.BigDecimal;

/** Common persistence input for one decoded result, with native unit and wire resolution. */
public final class ScaleReading {
    public final double kg;
    public final long measuredAt, receivedAt;
    public final String device, decoder, originalValue, originalUnit, originalResolution;
    public final Double impedanceOhms;

    ScaleReading(double kg, long measuredAt, long receivedAt, String device, String decoder,
                 String originalValue, String originalUnit, String originalResolution, Double impedanceOhms) {
        this.kg = kg;
        this.measuredAt = measuredAt;
        this.receivedAt = receivedAt;
        this.device = device;
        this.decoder = decoder;
        this.originalValue = originalValue;
        this.originalUnit = originalUnit;
        this.originalResolution = originalResolution;
        this.impedanceOhms = impedanceOhms;
    }

    static ScaleReading yoda(WeighFrame frame, long received) {
        return new ScaleReading(frame.centikg / 100.0, received, received, "Yoda0", WeighController.DECODER,
                BigDecimal.valueOf(frame.centikg, 2).toPlainString(), "kg", "0.01", null);
    }

    static ScaleReading broadcast(ScaleBroadcast.Reading reading, long received) {
        return new ScaleReading(reading.kg, reading.measuredAtMs == null ? received : reading.measuredAtMs,
                received, reading.model, reading.protocol + "-1", reading.originalValue, reading.originalUnit,
                reading.originalResolution, reading.impedanceOhms);
    }

    static ScaleReading standard(SigWeight.Reading value, String model, long received) {
        long measured = WeighSession.plausibleDeviceClock(value.deviceTimeMs, received) ? value.deviceTimeMs : received;
        return new ScaleReading(value.kg, measured, received, model, "sig-wss-1", value.originalValue,
                value.originalUnit, value.originalResolution, null);
    }
}
