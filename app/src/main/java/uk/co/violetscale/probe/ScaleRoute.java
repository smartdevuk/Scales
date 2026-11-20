package uk.co.violetscale.probe;

/** Positive protocol evidence is preferred to a generic service. Unknown devices require explicit selection. */
final class ScaleRoute {
    static final String YODA = "yoda0", SIG = "sig-wss", UNVERIFIED = "check-wss";
    final String protocol, model;
    ScaleRoute(String protocol, String model) {
        this.protocol = protocol;
        this.model = model;
    }
    boolean gatt() { return SIG.equals(protocol) || UNVERIFIED.equals(protocol); }

    static ScaleRoute identify(byte[] bytes, String name, String address, boolean weightService,
                               boolean connectable, ScaleRoute previous) {
        if (WeighFrame.parse(bytes) != null) return new ScaleRoute(YODA, "Yoda0");
        ScaleBroadcast.Reading reading = ScaleBroadcast.decode(bytes, name, address);
        if (reading != null) return new ScaleRoute(reading.protocol, reading.model);
        if (previous != null && !UNVERIFIED.equals(previous.protocol)) return previous;
        if (connectable && weightService) {
            String label = name == null || name.trim().isEmpty() ? "Bluetooth weight scale" : name.trim();
            return new ScaleRoute(SIG, label);
        }
        if (connectable && name != null && !name.trim().isEmpty()) return new ScaleRoute(UNVERIFIED, name.trim());
        return previous;
    }
}
