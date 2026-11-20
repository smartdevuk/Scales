package uk.co.violetscale.probe;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A foreground, time-limited BLE scan. Call its public methods on the UI thread. */
@SuppressLint("MissingPermission")
public final class Radio {
    public interface Listener {
        void onDevice(String address, String name, int rssi, Boolean connectable);
        void onPacket(JSONObject packet);
        void onRadioStatus(String text);
        void onScanEnded();
    }

    private static final long SCAN_WINDOW_MS = 120_000;
    private static final long DEVICE_REFRESH_MS = 900;
    private static final int MAX_DEVICES = 64;
    private static final int HISTORY_PER_DEVICE = 64;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final ParcelUuid WEIGHT_SCALE = ParcelUuid.fromString("0000181d-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid BODY_COMPOSITION = ParcelUuid.fromString("0000181b-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid VENDOR_SERVICE = ParcelUuid.fromString("0000fff0-0000-1000-8000-00805f9b34fb");

    private final BluetoothManager manager;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Access order lets new arrivals replace the device which was seen least recently.
    private final LinkedHashMap<String, Candidate> candidates =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Runnable timeout = () -> endScan("Scan finished. You can save this capture or scan again.");
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private BluetoothDevice selected;

    public Radio(Activity activity, Listener listener) {
        manager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        this.listener = listener;
    }

    public boolean start() {
        if (callback != null) return true;
        try {
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) {
                listener.onRadioStatus("Bluetooth is unavailable on this phone.");
                return false;
            }
            if (!adapter.isEnabled()) {
                listener.onRadioStatus("Turn on Bluetooth, then start a scan.");
                return false;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                listener.onRadioStatus("Bluetooth is starting. Try scanning again in a moment.");
                return false;
            }
            ScanSettings.Builder settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0);
            if (Build.VERSION.SDK_INT >= 26 && adapter.isLeExtendedAdvertisingSupported()) {
                settings.setLegacy(false);
            }
            candidates.clear();
            callback = new Results();
            scanner.startScan(null, settings.build(), callback);
            main.postDelayed(timeout, SCAN_WINDOW_MS);
            listener.onRadioStatus(selected == null
                    ? "Listening. Wake the scales and keep your phone nearby."
                    : "Listening for broadcasts from the selected device.");
            return true;
        } catch (SecurityException denied) {
            resetStart("Allow Bluetooth scanning in app permissions, then try again.");
        } catch (IllegalStateException unavailable) {
            resetStart("Bluetooth became unavailable. Turn it on and scan again.");
        }
        return false;
    }

    private void resetStart(String message) {
        callback = null;
        scanner = null;
        main.removeCallbacks(timeout);
        listener.onRadioStatus(message);
    }

    public void stop() {
        endScan("Scan stopped.");
    }

    private void endScan(String message) {
        if (callback == null) return;
        ScanCallback previous = callback;
        BluetoothLeScanner previousScanner = scanner;
        callback = null;
        scanner = null;
        main.removeCallbacks(timeout);
        try {
            previousScanner.stopScan(previous);
        } catch (SecurityException | IllegalStateException ignored) {
            // Permissions and the Bluetooth switch can change during a scan.
        }
        listener.onRadioStatus(message);
        listener.onScanEnded();
    }

    public boolean select(String address) {
        if (address != null && address.equals(selectedAddress())) return true;
        Candidate candidate = candidates.get(address);
        if (candidate == null) {
            listener.onRadioStatus("This device is no longer in the scan list. Wake it and scan again.");
            return false;
        }
        selected = candidate.device;
        while (!candidate.recent.isEmpty()) {
            emit(candidate.recent.removeFirst());
        }
        return true;
    }

    public void clearSelection() {
        selected = null;
    }

    public BluetoothDevice selectedDevice() {
        return selected;
    }

    public String selectedAddress() {
        return selected == null ? null : selected.getAddress();
    }

    public String clue(String address) {
        Candidate candidate = candidates.get(address);
        return candidate == null ? null : candidate.hint.label;
    }

    ScaleRoute route(String address) {
        Candidate candidate = candidates.get(address);
        return candidate == null ? null : candidate.route;
    }
    String advertisedName(String address) {
        Candidate candidate = candidates.get(address);
        return candidate == null ? null : candidate.displayName;
    }

    private final class Results extends ScanCallback {
        private void dispatch(Runnable action) {
            if (Looper.myLooper() == main.getLooper()) {
                if (callback == this) action.run();
            } else {
                main.post(() -> {
                    if (callback == this) action.run();
                });
            }
        }

        @Override public void onScanResult(int type, ScanResult result) {
            dispatch(() -> accept(result));
        }

        @Override public void onBatchScanResults(List<ScanResult> results) {
            dispatch(() -> {
                for (ScanResult result : results) accept(result);
            });
        }

        @Override public void onScanFailed(int code) {
            String help = code == 6 ? "Wait 30 seconds before scanning again."
                    : "Try scanning again. If it repeats, turn Bluetooth off and on.";
            dispatch(() -> endScan("Android scan error " + code + ". " + help));
        }
    }

    private static final class Candidate {
        final BluetoothDevice device;
        final ArrayDeque<Frame> recent = new ArrayDeque<>();
        String displayName;
        Hint hint = Hint.NONE;
        ScaleRoute route;
        long reportedAt = -DEVICE_REFRESH_MS;
        Candidate(BluetoothDevice device) { this.device = device; }
    }

    private enum Hint {
        NONE(null), FFF0("FFF0 service"), WEIGHT("Weight scale service"),
        COMPOSITION("Body composition service"), OKOK("OKOK scale candidate");

        final String label;
        Hint(String label) { this.label = label; }
    }

    private static Hint advertisedHint(ScanRecord record, Hint previous) {
        if (record == null || (previous != Hint.NONE && previous != Hint.FFF0)) return previous;
        if (advertisesService(record, WEIGHT_SCALE)) return Hint.WEIGHT;
        if (advertisesService(record, BODY_COMPOSITION)) return Hint.COMPOSITION;
        if (Decoder.advertisementBytes(record.getBytes()) != null) return Hint.OKOK;
        return advertisesService(record, VENDOR_SERVICE) ? Hint.FFF0 : previous;
    }

    private static boolean advertisesService(ScanRecord record, ParcelUuid uuid) {
        List<ParcelUuid> services = record.getServiceUuids();
        return (services != null && services.contains(uuid)) || record.getServiceData(uuid) != null;
    }

    private static final class Frame {
        final ScanResult result;
        final long timestampMs;
        Frame(ScanResult result) {
            this.result = result;
            long ageNanos = SystemClock.elapsedRealtimeNanos() - result.getTimestampNanos();
            timestampMs = System.currentTimeMillis() - Math.max(0L, ageNanos) / 1_000_000L;
        }
    }

    private void accept(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        BluetoothDevice device = result.getDevice();
        String address = device.getAddress();
        if (address == null) return;
        Candidate candidate = candidates.get(address);
        if (candidate == null) {
            if (candidates.size() >= MAX_DEVICES) {
                candidates.remove(candidates.keySet().iterator().next());
            }
            candidate = new Candidate(device);
            candidates.put(address, candidate);
        }
        ScanRecord record = result.getScanRecord();
        String name = record == null ? null : record.getDeviceName();
        boolean newName = name != null && !name.equals(candidate.displayName);
        if (newName) candidate.displayName = name;
        ScaleRoute oldRoute = candidate.route;
        boolean complete = Build.VERSION.SDK_INT < 26 || result.getDataStatus() == ScanResult.DATA_COMPLETE;
        Boolean linkable = connectable(result);
        if (complete && record != null) {
            candidate.route = ScaleRoute.identify(record.getBytes(), candidate.displayName, address,
                    advertisesService(record, WEIGHT_SCALE), !Boolean.FALSE.equals(linkable), oldRoute);
        }
        boolean routeChanged = candidate.route != null && (oldRoute == null
                || !candidate.route.protocol.equals(oldRoute.protocol));
        Hint hint = advertisedHint(record, candidate.hint);
        boolean newHint = hint != candidate.hint;
        candidate.hint = hint;

        Frame frame = new Frame(result);
        if (address.equals(selectedAddress())) {
            emit(frame);
        } else {
            if (candidate.recent.size() >= HISTORY_PER_DEVICE) candidate.recent.removeFirst();
            candidate.recent.addLast(frame);
        }

        long now = SystemClock.elapsedRealtime();
        if (newName || newHint || routeChanged || now - candidate.reportedAt >= DEVICE_REFRESH_MS) {
            candidate.reportedAt = now;
            listener.onDevice(address, candidate.displayName, result.getRssi(), connectable(result));
        }
    }

    private void emit(Frame frame) {
        try {
            listener.onPacket(packet(frame));
        } catch (JSONException malformed) {
            listener.onRadioStatus("Android returned an unreadable advertisement. Listening continues.");
        }
    }

    private static Boolean connectable(ScanResult result) {
        return Build.VERSION.SDK_INT >= 26 ? result.isConnectable() : null;
    }

    private static JSONObject packet(Frame frame) throws JSONException {
        ScanResult result = frame.result;
        ScanRecord record = result.getScanRecord();
        JSONObject event = new JSONObject();
        event.put("kind", "advertisement");
        event.put("timestampMs", frame.timestampMs);
        event.put("elapsedRealtimeNanos", result.getTimestampNanos());
        event.put("rssi", result.getRssi());
        Boolean connectable = connectable(result);
        event.put("connectable", connectable == null ? JSONObject.NULL : connectable);
        String name = record == null ? null : record.getDeviceName();
        event.put("name", name == null ? JSONObject.NULL : name);
        byte[] bytes = record == null ? null : record.getBytes();
        event.put("rawHex", bytes == null ? JSONObject.NULL : hex(bytes));
        if (Build.VERSION.SDK_INT >= 26) {
            event.put("dataStatus", result.getDataStatus());
            event.put("advertisingSid", result.getAdvertisingSid());
            event.put("legacy", result.isLegacy());
        }
        JSONArray services = new JSONArray();
        JSONArray serviceData = new JSONArray();
        JSONArray manufacturer = new JSONArray();
        if (record != null) {
            if (record.getServiceUuids() != null) {
                for (ParcelUuid id : record.getServiceUuids()) services.put(id.toString());
            }
            if (record.getServiceData() != null) {
                for (Map.Entry<ParcelUuid, byte[]> entry : record.getServiceData().entrySet()) {
                    serviceData.put(new JSONObject().put("uuid", entry.getKey().toString())
                            .put("dataHex", hex(entry.getValue())));
                }
            }
            SparseArray<byte[]> fields = record.getManufacturerSpecificData();
            if (fields != null) {
                for (int i = 0; i < fields.size(); i++) {
                    manufacturer.put(new JSONObject().put("companyId", fields.keyAt(i))
                            .put("dataHex", hex(fields.valueAt(i))));
                }
            }
        }
        event.put("serviceUuids", services);
        event.put("serviceData", serviceData);
        event.put("manufacturerData", manufacturer);
        return event;
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "";
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(chars);
    }
}
