package uk.co.violetscale.probe;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.UUID;

/** Foreground, user-initiated inspection of one selected peripheral. */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
public final class GattProbe {
    public interface Listener {
        void onGattEvent(JSONObject event);
        void onGattStatus(String text);
    }

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String SIG_BASE = "-0000-1000-8000-00805f9b34fb";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Operation> operations = new ArrayDeque<>();
    private BluetoothGatt active;
    private Operation pending;
    private Runnable deadline;
    private enum Phase { CONNECTING, DISCOVERING, INSPECTING }
    private Phase phase = Phase.CONNECTING;
    private int subscriptions;
    private final Runnable sessionEnd = () -> finish("Connection test finished after 90 seconds.");

    public GattProbe(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void start(BluetoothDevice device) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> start(device));
            return;
        }
        stop();
        if (device == null) {
            listener.onGattStatus("Choose the scale before testing a connection.");
            return;
        }
        listener.onGattStatus("Connecting to the scale…");
        emit("gatt_connecting", null);
        try {
            active = device.connectGatt(activity, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (active == null) {
                finish("Android could not start the connection. Wake the scale and try again.");
                return;
            }
            setDeadline(15000, "The scale did not accept a connection within 15 seconds.");
            main.postDelayed(sessionEnd, 90000);
        } catch (SecurityException e) {
            permissionDenied();
        } catch (RuntimeException e) {
            finish("Bluetooth could not start the connection (" + e.getClass().getSimpleName() + ").");
        }
    }

    public void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::stop);
            return;
        }
        if (active != null) finish("Connection test stopped.");
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) {
            main.post(() -> {
                if (gatt != active) return;
                emit("gatt_state", null, "status", status, "state", state);
                if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    finish("Scale disconnected (Bluetooth status " + status + ").");
                } else if (state == BluetoothProfile.STATE_CONNECTED && phase == Phase.CONNECTING) {
                    emit("gatt_connected", null);
                    listener.onGattStatus("Connected. Discovering services…");
                    phase = Phase.DISCOVERING;
                    try {
                        if (gatt.discoverServices()) {
                            setDeadline(12000, "Service discovery timed out.");
                        } else {
                            finish("Android could not start service discovery.");
                        }
                    } catch (SecurityException e) {
                        permissionDenied();
                    } catch (RuntimeException e) {
                        finish("Android could not discover services (" + e.getClass().getSimpleName() + ").");
                    }
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            main.post(() -> {
                if (gatt != active || phase != Phase.DISCOVERING) return;
                phase = Phase.INSPECTING;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish("Service discovery failed (Bluetooth status " + status + ").");
                    return;
                }
                cancelDeadline();
                inventory(gatt);
                next();
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c) {
            receiveValue(gatt, c, c.getValue(), true, BluetoothGatt.GATT_SUCCESS);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] value) {
            receiveValue(gatt, c, value, true, BluetoothGatt.GATT_SUCCESS);
        }

        @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic c, int status) {
            receiveValue(gatt, c, c.getValue(), false, status);
        }

        @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] value, int status) {
            receiveValue(gatt, c, value, false, status);
        }

        @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            main.post(() -> {
                BluetoothGattCharacteristic c = descriptor.getCharacteristic();
                if (gatt != active || !CCCD.equals(descriptor.getUuid()) || !matches(c, true)) return;
                if (status == BluetoothGatt.GATT_SUCCESS) subscriptions++;
                emit(status == BluetoothGatt.GATT_SUCCESS ? "gatt_subscribed" : "gatt_subscribe_failed",
                        c, "status", status, "mode", subscriptionMode(c));
                next();
            });
        }

        @Override public void onServiceChanged(BluetoothGatt gatt) {
            main.post(() -> {
                if (gatt == active) finish("The scale changed its services. Run the connection test again.");
            });
        }
    };

    private void receiveValue(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] value,
                              boolean notification, int status) {
        // Legacy Android can reuse the characteristic's mutable byte array after a callback.
        final String bytes = status == BluetoothGatt.GATT_SUCCESS && value != null ? hex(value) : null;
        final long wall = System.currentTimeMillis();
        final long elapsed = SystemClock.elapsedRealtime();
        main.post(() -> {
            if (active != gatt || (!notification && !matches(c, false))) return;
            String type = notification ? "gatt_notification" : "gatt_read";
            if (status != BluetoothGatt.GATT_SUCCESS) type = "gatt_read_failed";
            emit(type, c, "valueHex", bytes, "status", status, "timestampMs", wall, "elapsedMs", elapsed);
            if (!notification) next();
        });
    }

    private void inventory(BluetoothGatt gatt) {
        ArrayDeque<Operation> reads = new ArrayDeque<>();
        for (BluetoothGattService service : gatt.getServices()) {
            String serviceId = service.getUuid().toString();
            emit("gatt_service", null, "serviceUuid", serviceId, "serviceInstance", service.getInstanceId(),
                    "primary", service.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY);
            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                JSONArray descriptors = new JSONArray();
                for (BluetoothGattDescriptor d : c.getDescriptors()) descriptors.put(d.getUuid().toString());
                int flags = c.getProperties();
                emit("gatt_characteristic", c, "properties", flags, "descriptors", descriptors);
                if (!inspectValue(serviceId, c.getUuid().toString())) continue;
                if ((flags & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                    if (c.getDescriptor(CCCD) != null) {
                        operations.add(new Operation(c, true));
                    } else {
                        emit("gatt_subscription_unavailable", c, "reason", "No client configuration descriptor");
                    }
                }
                if ((flags & BluetoothGattCharacteristic.PROPERTY_READ) != 0) reads.add(new Operation(c, false));
            }
        }
        // Arm live streams before issuing optional one-shot reads.
        operations.addAll(reads);
    }

    private static boolean inspectValue(String service, String characteristic) {
        // Inspect vendor services and relevant SIG measurements. For Device Information,
        // limit reads to model, revisions and manufacturer, excluding serial and System ID.
        if (!service.startsWith("000018") || !service.endsWith(SIG_BASE)) return true;
        switch (service.substring(4, 8)) {
            case "180a":
                return characteristic.matches("00002a(24|26|27|28|29)" + SIG_BASE);
            case "180d": // Heart Rate
            case "180f": // Battery Service
            case "181b": // Body Composition
            case "181d": // Weight Scale
                return true;
            default:
                return false;
        }
    }

    private static final class Operation {
        final BluetoothGattCharacteristic target;
        final boolean subscribe;
        Operation(BluetoothGattCharacteristic target, boolean subscribe) {
            this.target = target;
            this.subscribe = subscribe;
        }
    }

    private boolean matches(BluetoothGattCharacteristic c, boolean subscribe) {
        if (pending == null || pending.subscribe != subscribe) return false;
        BluetoothGattCharacteristic target = pending.target;
        return target.getInstanceId() == c.getInstanceId() && target.getUuid().equals(c.getUuid())
                && target.getService().getInstanceId() == c.getService().getInstanceId()
                && target.getService().getUuid().equals(c.getService().getUuid());
    }

    private static String subscriptionMode(BluetoothGattCharacteristic c) {
        return (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ? "notify" : "indicate";
    }

    private void next() {
        cancelDeadline();
        while ((pending = operations.poll()) != null && active != null) {
            BluetoothGattCharacteristic c = pending.target;
            try {
                boolean started;
                if (pending.subscribe) {
                    started = subscribe(c);
                } else {
                    started = active.readCharacteristic(c);
                }
                if (started) {
                    setDeadline(7000, "A Bluetooth operation timed out. Wake the scale and try again.");
                    return;
                }
                emit("gatt_operation_rejected", c, "operation", pending.subscribe ? "subscribe" : "read");
            } catch (SecurityException e) {
                permissionDenied();
                return;
            } catch (RuntimeException e) {
                finish("The Bluetooth operation failed (" + e.getClass().getSimpleName() + ").");
                return;
            }
        }
        listener.onGattStatus(subscriptions > 0 ? "Connected and listening. Step on the scale."
                : "Services inspected. No measurement notifications were enabled.");
    }

    private boolean subscribe(BluetoothGattCharacteristic c) {
        if (!active.setCharacteristicNotification(c, true)) return false;
        BluetoothGattDescriptor config = c.getDescriptor(CCCD);
        byte[] value = "notify".equals(subscriptionMode(c))
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        // The subscription descriptor is the only remote value this client ever writes.
        if (Build.VERSION.SDK_INT >= 33) return active.writeDescriptor(config, value) == 0;
        return config.setValue(value) && active.writeDescriptor(config);
    }

    private void setDeadline(int milliseconds, String message) {
        cancelDeadline();
        deadline = () -> {
            if (pending != null) emit("gatt_operation_timeout", pending.target,
                    "operation", pending.subscribe ? "subscribe" : "read");
            finish(message);
        };
        main.postDelayed(deadline, milliseconds);
    }

    private void cancelDeadline() {
        if (deadline != null) main.removeCallbacks(deadline);
        deadline = null;
    }

    private void permissionDenied() {
        finish("Bluetooth access was denied. Allow Nearby devices and try again.");
    }

    private void finish(String reason) {
        BluetoothGatt gatt = active;
        active = null;
        cancelDeadline();
        main.removeCallbacks(sessionEnd);
        pending = null;
        operations.clear();
        phase = Phase.CONNECTING;
        subscriptions = 0;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (RuntimeException ignored) { }
            try { gatt.close(); } catch (RuntimeException ignored) { }
        }
        emit("gatt_end", null, "reason", reason);
        listener.onGattStatus(reason);
    }

    private void emit(String type, BluetoothGattCharacteristic c, Object... fields) {
        JSONObject event = new JSONObject();
        try {
            event.put("type", type);
            event.put("timestampMs", System.currentTimeMillis());
            event.put("elapsedMs", SystemClock.elapsedRealtime());
            if (c != null) {
                event.put("serviceUuid", c.getService().getUuid().toString());
                event.put("serviceInstance", c.getService().getInstanceId());
                event.put("characteristicUuid", c.getUuid().toString());
                event.put("characteristicInstance", c.getInstanceId());
            }
            for (int i = 0; i < fields.length; i += 2) event.put((String) fields[i], fields[i + 1]);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        listener.onGattEvent(event);
    }

    private static String hex(byte[] value) {
        char[] output = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            int b = value[i] & 255;
            output[2 * i] = HEX[b >>> 4];
            output[2 * i + 1] = HEX[b & 15];
        }
        return new String(output);
    }
}
