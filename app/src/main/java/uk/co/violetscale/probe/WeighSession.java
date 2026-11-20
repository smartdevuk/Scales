package uk.co.violetscale.probe;

/** One foreground session. Timestamps are receipt times on the Android monotonic clock. */
final class WeighSession {
    enum Signal { IGNORE, STARTING, STABILISING, CHECK, READY }
    private static final long START_VALID_MS = 25_000, MAX_GAP_MS = 1_500, MIN_STABLE_MS = 800;
    private int sequence = -1, weight, count;
    private long lastTime = -1, armedAt = -1, firstResult;
    private boolean blocked, issued;

    Signal accept(WeighFrame frame, long now) {
        if (frame == null || now <= lastTime) return Signal.IGNORE;
        long gap = now - lastTime;
        lastTime = now;
        if (frame.sequence != sequence) {
            sequence = frame.sequence;
            armedAt = -1;
            blocked = issued = false;
            count = weight = 0;
        }
        if (frame.centikg == 0) {
            if (weight != 0 || issued) return Signal.IGNORE; // Reordered startup packet.
            armedAt = now;
            return Signal.STARTING;
        }
        if (issued && frame.centikg == weight) return Signal.IGNORE;
        if (weight != 0 && weight != frame.centikg) blocked = true;
        if (weight != frame.centikg || gap > MAX_GAP_MS) {
            weight = frame.centikg;
            firstResult = now;
            count = 0;
            issued = false;
        }
        if (++count < 3 || now - firstResult < MIN_STABLE_MS) return Signal.STABILISING;
        issued = true;
        return !blocked && armedAt >= 0 && now - armedAt <= START_VALID_MS ? Signal.READY : Signal.CHECK;
    }

    static boolean needsOwnerCheck(Double previous, double kg) {
        return previous == null || Math.abs(previous - kg) > Math.max(3.0, previous * 0.05);
    }

    static boolean repeats(String signature, long when, String previousSignature, long previousTime) {
        // An 8-bit counter can wrap. Deduplicate only within a recent broadcast window.
        long age = when - previousTime;
        return signature.equals(previousSignature) && age >= -2_000 && age <= 180_000;
    }

    static boolean plausibleDeviceClock(Long timestamp, long received) {
        return timestamp != null && timestamp >= 946684800000L && timestamp <= received + 60_000;
    }

    static boolean freshDeviceClock(Long timestamp, long received) {
        return plausibleDeviceClock(timestamp, received) && Math.abs(received - timestamp) <= 30_000;
    }

    /**
     * For advertisements with an explicit live/final bit. A clear/removed packet can follow either
     * phase and cannot arm a later final. Some broadcast scales transmit just one final packet.
     */
    static final class BroadcastCycle {
        private long lastTime = -1, started = -1, lastLive = -1;
        private String liveKey, finalKey;

        boolean observeLive(String key, long now) {
            if (now <= lastTime) return false;
            if (lastLive < 0 || now - lastLive > 5_000 || !java.util.Objects.equals(key, liveKey)) started = now;
            lastTime = lastLive = now;
            liveKey = key;
            finalKey = null;
            return true;
        }

        void clear(long now) {
            if (now <= lastTime) return;
            lastTime = now;
            started = lastLive = -1;
            liveKey = null;
        }

        Signal finish(String key, String valueKey, long now) {
            if (now <= lastTime || valueKey.equals(finalKey)) return Signal.IGNORE;
            lastTime = now;
            boolean fresh = lastLive >= 0 && now - lastLive <= 10_000
                    && (key == null || liveKey == null || key.equals(liveKey));
            finalKey = valueKey;
            lastLive = -1;
            return fresh ? Signal.READY : Signal.CHECK;
        }

        long started() { return started; }
    }
}
