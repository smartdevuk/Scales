package uk.co.violetscale.probe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/** Foreground weighing for recognised broadcast scales and the standard Weight Scale Service. */
public final class WeighController implements Radio.Listener {
    public static final String DECODER = "yoda0-1";
    private static final int PERMISSIONS = 4101, ENABLE_BLUETOOTH = 4102;

    public interface Listener {
        void onWeighState(String state, String message, Double kg);
        boolean onWeighReading(String sessionId, ScaleReading reading);
        boolean hasWeighReading(String sessionId);
        boolean onWeighImpedance(String sessionId, double ohms);
        Double previousWeightKg();
    }

    private final Activity activity;
    private final Listener listener;
    private final Radio radio;
    private final SharedPreferences prefs;
    private final LinkedHashMap<String, String> choices = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> otherDevices = new LinkedHashMap<>();
    private String state = "idle", message = "", scanStatus = "";
    private Double shownKg;
    private boolean running, quiet, requested, foreground;
    private long scanStarted;
    private WeighSession session = new WeighSession();
    private WeighSession.BroadcastCycle cycle = new WeighSession.BroadcastCycle();
    private ScaleRoute route;
    private WeightGatt gatt;
    private int generation;
    private String deviceAddress, advertisedName;
    private ScaleReading pending;
    private String pendingSignature, fixedId;
    private Integer pendingUser;
    private boolean separateCycle, intentionalRepeat;
    private boolean canEnrich;
    private String lateId, lateSignature;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable endLateWindow = this::stopScan;

    public WeighController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.radio = new Radio(activity, this);
        this.prefs = activity.getSharedPreferences("weigh-radio", Context.MODE_PRIVATE);
    }

    public void start() {
        foreground = true;
        if (running || gatt != null || requested) return;
        if (!allowed()) {
            update("permission", Build.VERSION.SDK_INT >= 31
                    ? "Allow Nearby devices to listen for your scales."
                    : "Allow location while using Scales so Android can scan for your scales.", null);
            return;
        }
        beginScan();
    }

    public void action() {
        switch (state) {
            case "permission":
                if (allowed()) { start(); break; }
                if (prefs.getBoolean("asked", false) && permanentlyDenied()) {
                    open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.getPackageName())));
                } else {
                    requested = true;
                    prefs.edit().putBoolean("asked", true).apply();
                    activity.requestPermissions(requiredPermissions(), PERMISSIONS);
                }
                break;
            case "bluetooth":
                if (!allowed()) { start(); break; }
                try { activity.startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH); }
                catch (RuntimeException ex) { open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
                break;
            case "location":
                open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); break;
            case "choose":
                choose(); break;
            case "confirm":
                if (pending != null) savePending(); break;
            case "connecting":
                break;
            case "saved": case "duplicate":
                stopScan();
                intentionalRepeat = true;
                start();
                break;
            case "searching": case "listening": case "stabilising":
                if (!choices.isEmpty() && radio.selectedAddress() == null) choose();
                break;
            default:
                stopScan(); start();
        }
    }

    private void open(Intent intent) {
        try { activity.startActivity(intent); }
        catch (RuntimeException ex) { update("error", "Open Android Settings, then try listening again.", null); }
    }

    public void permissionsResult(int request) {
        if (request != PERMISSIONS) return;
        requested = false;
        if (!foreground) return;
        if (allowed()) start();
        else update("permission", "Nearby scanning needs permission. You can still add readings manually.", null);
    }

    public void activityResult(int request, int result) {
        if (request == ENABLE_BLUETOOTH && foreground) {
            if (result == Activity.RESULT_OK) start();
            else update("bluetooth", "Turn on Bluetooth to listen for your scales.", null);
        }
    }

    private String[] requiredPermissions() {
        return Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean allowed() {
        for (String permission : requiredPermissions()) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private boolean permanentlyDenied() {
        for (String permission : requiredPermissions()) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
                    && !activity.shouldShowRequestPermissionRationale(permission)) return true;
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private void beginScan() {
        BluetoothManager manager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            if (adapter == null) { update("error", "This phone has no Bluetooth adapter. Add a reading manually.", null); return; }
            if (!adapter.isEnabled()) { update("bluetooth", "Turn on Bluetooth to listen for your scales.", null); return; }
            if (Build.VERSION.SDK_INT <= 30 && !locationEnabled()) {
                update("location", "Turn on Location in Android Settings to find nearby scales.", null);
                return;
            }
        } catch (SecurityException denied) {
            update("permission", "Allow Bluetooth access to listen for your scales.", null);
            return;
        }
        radio.clearSelection();
        choices.clear();
        otherDevices.clear();
        route = null;
        deviceAddress = advertisedName = null;
        pending = null;
        session = new WeighSession();
        cycle = new WeighSession.BroadcastCycle();
        scanStarted = SystemClock.elapsedRealtime();
        scanStatus = "";
        running = radio.start();
        if (running) update("searching", "Step on your scales and stay still.", null);
        else update("error", scanStatus.isEmpty() ? "Couldn’t start listening. Try again." : scanStatus, null);
    }

    private boolean locationEnabled() {
        if (Build.VERSION.SDK_INT >= 28) {
            LocationManager location = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
            return location != null && location.isLocationEnabled();
        }
        return Settings.Secure.getInt(activity.getContentResolver(), Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
    }

    public void stop() {
        foreground = false;
        intentionalRepeat = false;
        stopScan();
    }

    private void stopScan() {
        main.removeCallbacks(endLateWindow);
        lateId = lateSignature = null;
        generation++;
        if (gatt != null) { gatt.stop(); gatt = null; }
        quiet = true;
        radio.stop();
        running = quiet = false;
    }

    public void forgetDevice() {
        prefs.edit().remove("selected").remove("protocol").remove("model").apply();
        stopScan();
        radio.clearSelection();
        choices.clear();
        otherDevices.clear();
        pending = null;
        update("idle", "Choose your scales at the next weigh-in.", null);
        if (foreground) start();
    }

    public String pairedDeviceName() { return prefs.getString("model", prefs.contains("selected") ? "Yoda0" : ""); }

    @Override public void onDevice(String address, String name, int rssi, Boolean connectable) {
        if (!running || radio.selectedAddress() != null) return;
        ScaleRoute found = radio.route(address);
        if (found == null || (found.gatt() && Boolean.FALSE.equals(connectable))) return;
        boolean unverified = ScaleRoute.UNVERIFIED.equals(found.protocol);
        String remembered = prefs.getString("protocol", ScaleRoute.YODA);
        boolean compatible = remembered.equals(found.protocol) || (unverified && ScaleRoute.SIG.equals(remembered));
        if (compatible && address.equals(prefs.getString("selected", ""))) {
            select(address, found);
            return;
        }
        String suffix = address.substring(Math.max(0, address.length() - 5));
        String label = found.model + " · " + suffix + "   (" + rssi + " dBm)";
        if (unverified) {
            if (!otherDevices.containsKey(address) && otherDevices.size() >= 64) return;
            otherDevices.put(address, label);
        } else {
            otherDevices.remove(address);
            if (!choices.containsKey(address) && choices.size() >= 64) return;
            choices.put(address, label);
        }
        if (!choices.isEmpty()) {
            update("choose", "Choose your nearby scales to start weighing.", null);
        } else if (SystemClock.elapsedRealtime() - scanStarted > 8_000) {
            update("choose", "Wake your scales. If they’re already awake, choose a Bluetooth device to check.", null);
        }
    }

    private void choose() {
        if (choices.isEmpty() && otherDevices.isEmpty()) { stopScan(); start(); return; }
        if (choices.isEmpty()) { showDevices(otherDevices, true); return; }
        showDevices(choices, false);
    }

    private void showDevices(LinkedHashMap<String, String> devices, boolean checking) {
        ArrayList<String> labels = new ArrayList<>(devices.values());
        ArrayList<String> addresses = new ArrayList<>(devices.keySet());
        if (!checking && !otherDevices.isEmpty()) labels.add("Check another Bluetooth device…");
        new AlertDialog.Builder(activity).setTitle("Choose your scales")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    if (!running || radio.selectedAddress() != null) return;
                    if (index == addresses.size()) { showDevices(otherDevices, true); return; }
                    String address = addresses.get(index);
                    ScaleRoute selected = radio.route(address);
                    if (selected != null) select(address, selected);
                }).setNegativeButton("Cancel", null).show();
    }

    private void select(String address, ScaleRoute selected) {
        route = selected;
        deviceAddress = address;
        advertisedName = radio.advertisedName(address);
        if (!selected.gatt()) {
            remember(selected.protocol);
            update("listening", "Listening to " + selected.model + ". Step on and let the display settle.", null);
        }
        if (!radio.select(address)) {
            stopScan();
            update("error", "Your scales went out of range. Wake them and try again.", null);
            return;
        }
        if (selected.gatt()) connectStandard();
    }

    private void remember(String protocol) {
        prefs.edit().putString("selected", deviceAddress).putString("protocol", protocol)
                .putString("model", route.model).apply();
    }

    private void connectStandard() {
        quiet = true;
        radio.stop();
        running = quiet = false;
        final int operation = ++generation;
        gatt = new WeightGatt(activity, new WeightGatt.Listener() {
            public void onGattState(String status) {
                if (operation == generation && foreground && pending == null)
                    update("connecting", status, null);
            }
            public void onGattReading(SigWeight.Reading value) {
                if (operation == generation && foreground) receiveStandard(value);
            }
            public void onGattError(String reason) {
                if (operation != generation || !foreground) return;
                gatt = null;
                if (pending == null) update("error", reason, null);
            }
        });
        update("connecting", "Checking the weight service on " + route.model + "…", null);
        gatt.start(radio.selectedDevice());
    }

    private void receiveStandard(SigWeight.Reading value) {
        long now = System.currentTimeMillis();
        Long clock = value.deviceTimeMs;
        ScaleReading reading = ScaleReading.standard(value, route.model, now);
        boolean plausibleDate = WeighSession.plausibleDeviceClock(clock, now);
        boolean fresh = WeighSession.freshDeviceClock(clock, now);
        String reason = null;
        if (!fresh) {
            reason = clock == null ? "This scale sends weights without a time. Confirm this is your current reading."
                    : plausibleDate ? "The scale dated this reading " + DateFormat.getDateTimeInstance()
                            .format(new Date(reading.measuredAt)) + ". Confirm it belongs in your history."
                    : "The scale clock looks incorrect. Confirm to use the time received.";
        }
        Integer user = value.userId;
        if (user != null && (user == 255 || prefs.getInt("slot/" + deviceAddress, -1) != user)) {
            String slot = user == 255 ? "an unknown user" : "scale user " + user;
            reason = (reason == null ? "" : reason + " ") + "Confirm the reading for " + slot + " is yours.";
        }
        remember(ScaleRoute.SIG);
        String key = value.sampleKey.isEmpty() ? null : value.sampleKey;
        String sig = signature(ScaleRoute.SIG, key, value.originalUnit, value.originalValue);
        String stableId = key == null ? null : UUID.nameUUIDFromBytes(sig.getBytes(StandardCharsets.UTF_8)).toString();
        boolean repeatedOnRequest = intentionalRepeat && key == null && matchesRecent(sig, now);
        if (repeatedOnRequest) reason = "This matches a recent weight. Confirm only if you’ve just weighed again.";
        offer(reading, sig, stableId, user, repeatedOnRequest, false, reason);
    }

    @Override public void onPacket(JSONObject packet) {
        if (!running || route == null || route.gatt() || !"advertisement".equals(packet.optString("kind"))
                || packet.optInt("dataStatus", 0) != 0) return;
        byte[] bytes = Decoder.fromHex(packet.optString("rawHex"));
        long elapsed = packet.optLong("elapsedRealtimeNanos", -1) / 1_000_000L;
        if (elapsed < scanStarted || elapsed > SystemClock.elapsedRealtime()) return;
        long received = packet.optLong("timestampMs", System.currentTimeMillis());
        if (!ScaleRoute.YODA.equals(route.protocol)) {
            String name = packet.isNull("name") ? advertisedName : packet.optString("name");
            ScaleBroadcast.Reading value = ScaleBroadcast.decode(bytes, name, deviceAddress);
            if (value != null && route.protocol.equals(value.protocol)) receiveBroadcast(value, elapsed, received);
            return;
        }
        WeighFrame frame = WeighFrame.parse(bytes);
        if (frame == null) return;
        WeighSession.Signal signal = session.accept(frame, elapsed);
        if (signal == WeighSession.Signal.IGNORE) return;
        if (signal == WeighSession.Signal.STARTING) {
            pending = null;
            update("listening", "Scales awake. Stay still while they take your reading.", null);
            return;
        }
        double kg = frame.centikg / 100.0;
        if (signal == WeighSession.Signal.STABILISING) {
            pending = null;
            update("stabilising", "Reading received. Hold still for a moment.", kg);
            return;
        }
        ScaleReading reading = ScaleReading.yoda(frame, received);
        // Preserve the existing identity signature so an update cannot save a recent packet twice.
        String sig = deviceAddress + "/" + frame.sequence + "/" + frame.centikg;
        offer(reading, sig, null, null, false, false, needsConfirmation(signal, elapsed));
    }

    private void receiveBroadcast(ScaleBroadcast.Reading value, long elapsed, long received) {
        if (lateId != null) {
            if (value.stable && value.kg != null && value.impedanceOhms != null && value.sessionKey != null
                    && lateSignature.equals(signature(value.protocol, value.sessionKey, value.originalUnit, value.originalValue))) {
                try { listener.onWeighImpedance(lateId, value.impedanceOhms); }
                catch (RuntimeException unavailable) { /* The primary weight is already durable. */ }
                finally { stopScan(); }
            }
            return;
        }
        if (value.start) {
            if (cycle.observeLive(value.sessionKey, elapsed)) {
                pending = null;
                update("stabilising", "Stay still while the scale settles.", value.kg);
            }
            return;
        }
        if (!value.stable || value.kg == null) { cycle.clear(elapsed); return; }
        String sig = signature(value.protocol, value.sessionKey, value.originalUnit, value.originalValue);
        boolean timestampedComposition = ScaleBroadcast.XIAOMI_V2.equals(value.protocol) && value.sessionKey != null;
        if (pending != null && timestampedComposition && sig.equals(pendingSignature)
                && pending.impedanceOhms == null && value.impedanceOhms != null) {
            pending = new ScaleReading(pending.kg, pending.measuredAt, pending.receivedAt, pending.device,
                    pending.decoder, pending.originalValue, pending.originalUnit, pending.originalResolution, value.impedanceOhms);
            return;
        }
        WeighSession.Signal signal = cycle.finish(value.sessionKey, sig, elapsed);
        if (signal == WeighSession.Signal.IGNORE) return;
        boolean independent = value.sessionKey == null && signal == WeighSession.Signal.READY
                && received - (elapsed - cycle.started()) > prefs.getLong("seen", 0) + 2_000;
        boolean repeatedOnRequest = intentionalRepeat && value.sessionKey == null && matchesRecent(sig, received);
        ScaleReading reading = ScaleReading.broadcast(value, received);
        String reason = repeatedOnRequest && !independent
                ? "This matches a recent weight. Confirm only if you’ve just weighed again."
                : needsConfirmation(signal, elapsed);
        offer(reading, sig, null, null, independent || repeatedOnRequest, timestampedComposition, reason);
    }

    private String signature(String protocol, String token, String unit, String value) {
        return deviceAddress + "/" + protocol + "/" + (token == null ? "none" : token) + "/" + unit + "/" + value;
    }

    private String needsConfirmation(WeighSession.Signal signal, long elapsed) {
        return signal == WeighSession.Signal.CHECK || SystemClock.elapsedRealtime() - elapsed > 3_000
                ? "The scales were already showing this weight. Confirm it’s your weigh-in." : null;
    }

    private void offer(ScaleReading reading, String sig, String stableId, Integer user,
                       boolean independent, boolean timestampedComposition, String reason) {
        boolean repeated = !independent && matchesRecent(sig, reading.receivedAt) && prefs.getBoolean("saved", false);
        boolean stored = false;
        if (stableId != null) {
            try { stored = listener.hasWeighReading(stableId); }
            catch (RuntimeException unavailable) {
                stopScan();
                update("error", "Couldn’t open your weight history. Try again.", null);
                return;
            }
        }
        if (repeated || stored) {
            // A duplicate in the GATT backlog must not displace an unsaved record under review.
            if (pending != null && !sig.equals(pendingSignature)) return;
            pending = null;
            update("duplicate", "Already recorded. Step off, then back on for another reading.", reading.kg);
            return;
        }
        // Prefer the newest buffered record. Never replace it with an older one under review.
        if (pending != null && gatt != null && reason != null && !sig.equals(pendingSignature)
                && pending.measuredAt >= reading.measuredAt) return;
        pending = reading;
        pendingSignature = sig;
        fixedId = stableId;
        pendingUser = user;
        separateCycle = independent;
        canEnrich = timestampedComposition;
        if (reason == null && WeighSession.needsOwnerCheck(listener.previousWeightKg(), reading.kg))
            reason = "Check the displayed weight and confirm this reading is yours.";
        if (reason != null) update("confirm", reason, reading.kg);
        else savePending();
    }

    private boolean matchesRecent(String sig, long time) {
        return WeighSession.repeats(sig, time, prefs.getString("signature", ""),
                prefs.getLong("seen", 0));
    }

    private void savePending() {
        if (pending == null) return;
        double kg = pending.kg;
        boolean same = !separateCycle && matchesRecent(pendingSignature, pending.receivedAt);
        String id = fixedId != null ? fixedId : same ? prefs.getString("id", null) : null;
        if (id == null) id = UUID.randomUUID().toString();
        // Reserve before writing history, so interruption can retry with the same database key.
        boolean reserved = prefs.edit().putString("signature", pendingSignature).putString("id", id)
                .putLong("seen", pending.receivedAt).putBoolean("saved", false).commit();
        if (reserved) separateCycle = false; // Retry a failed storage operation with the reserved ID.
        boolean saved = false;
        if (reserved) {
            try { saved = listener.onWeighReading(id, pending); }
            catch (RuntimeException storageError) { saved = false; }
        }
        if (!saved) {
            stopScan();
            update("confirm", "Couldn’t save this reading. Tap to try saving it again.", kg);
            return;
        }
        intentionalRepeat = false;
        SharedPreferences.Editor savedState = prefs.edit().putBoolean("saved", true);
        if (pendingUser != null && pendingUser != 255) savedState.putInt("slot/" + deviceAddress, pendingUser);
        savedState.commit();
        if (running && canEnrich && pending.impedanceOhms == null) {
            lateId = id;
            lateSignature = pendingSignature;
            main.postDelayed(endLateWindow, 12_000);
        } else stopScan();
        pending = null;
        update("saved", "Weigh-in saved to your history.", kg);
    }

    @Override public void onRadioStatus(String text) { scanStatus = text; }

    @Override public void onScanEnded() {
        running = false;
        if (quiet || lateId != null) return;
        if (pending != null && "confirm".equals(state)) return;
        String reason = scanStatus.startsWith("Android scan error") ? scanStatus
                : "No new reading. Wake your scales and try again.";
        update("error", reason, null);
    }

    private void update(String next, String text, Double kg) {
        if (state.equals(next) && message.equals(text) && Objects.equals(shownKg, kg)) return;
        state = next; message = text; shownKg = kg;
        listener.onWeighState(next, text, kg);
    }
}
