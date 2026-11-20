package uk.co.violetscale.probe;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.TimeZone;
import java.util.UUID;

/** One foreground measurement from a user-selected standard Bluetooth Weight Scale Service. */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
final class WeightGatt {
    interface Listener {
        void onGattState(String message);
        void onGattReading(SigWeight.Reading reading);
        void onGattError(String reason);
    }

    static final UUID SERVICE = UUID.fromString("0000181d-0000-1000-8000-00805f9b34fb");
    private static final UUID MEASUREMENT = UUID.fromString("00002a9d-0000-1000-8000-00805f9b34fb"),
            CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private enum Phase { IDLE, CONNECTING, DISCOVERING, SUBSCRIBING, LISTENING }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothGatt active;
    private BluetoothGattCharacteristic target;
    private Phase phase = Phase.IDLE;
    private Runnable deadline;
    private boolean invalidReceived;

    WeightGatt(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start(BluetoothDevice device) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> start(device)); return; }
        stop();
        if (device == null) { fail("Choose a scale to connect."); return; }
        try {
            phase = Phase.CONNECTING;
            active = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (active == null) { fail("Android could not connect. Wake the scale and try again."); return; }
            timeout(15000, "The scale did not accept a connection. Wake it and try again.");
            listener.onGattState("Connecting to your scale…");
        } catch (RuntimeException e) { bluetoothError(e); }
    }

    void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::stop); return; }
        cancelTimeout();
        BluetoothGatt connection = active;
        active = null;
        target = null;
        phase = Phase.IDLE;
        invalidReceived = false;
        if (connection != null) {
            try { connection.disconnect(); } catch (RuntimeException ignored) { }
            try { connection.close(); } catch (RuntimeException ignored) { }
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) {
            main.post(() -> {
                if (gatt != active) return;
                if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("The scale disconnected. Wake it and try again.");
                } else if (state == BluetoothProfile.STATE_CONNECTED && phase == Phase.CONNECTING) {
                    phase = Phase.DISCOVERING;
                    try {
                        if (!gatt.discoverServices()) { fail("Android could not discover the scale’s services."); return; }
                        timeout(12000, "The scale took too long to identify its services. Try again.");
                        listener.onGattState("Connected. Checking for weight measurements…");
                    } catch (RuntimeException e) { bluetoothError(e); }
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            main.post(() -> {
                if (gatt != active || phase != Phase.DISCOVERING) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Service discovery failed. Wake the scale and try again."); return; }
                try { subscribe(gatt); } catch (RuntimeException e) { bluetoothError(e); }
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            main.post(() -> {
                if (gatt != active || phase != Phase.SUBSCRIBING || descriptor == null
                        || !CCCD.equals(descriptor.getUuid()) || !matches(descriptor.getCharacteristic())) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("The scale declined the weight subscription. Check its pairing settings and try again."); return;
                }
                phase = Phase.LISTENING;
                timeout(45000, "No weight arrived. Wake the scale and weigh again.");
                listener.onGattState("Connected and listening. Step on the scale.");
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            receive(gatt, characteristic, characteristic.getValue());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            receive(gatt, characteristic, value);
        }

        @Override public void onServiceChanged(BluetoothGatt gatt) {
            main.post(() -> { if (gatt == active) fail("The scale changed its services. Reconnect to weigh again."); });
        }
    };

    private void subscribe(BluetoothGatt gatt) {
        // Resolve the exact service and characteristic. A generic vendor service can reuse descriptors or IDs.
        for (BluetoothGattService service : gatt.getServices()) {
            if (!SERVICE.equals(service.getUuid())) continue;
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if (!MEASUREMENT.equals(characteristic.getUuid())) continue;
                if (target != null) { fail("This device has multiple weight channels. Open Bluetooth diagnostics to inspect it."); return; }
                target = characteristic;
            }
        }
        if (target == null || (target.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) {
            fail("This device does not offer standard weight indications. Choose another scale or open Bluetooth diagnostics.");
            return;
        }
        BluetoothGattDescriptor config = target.getDescriptor(CCCD);
        if (config == null || !gatt.setCharacteristicNotification(target, true)) {
            fail("Android could not enable the scale’s weight indications."); return;
        }
        phase = Phase.SUBSCRIBING;
        byte[] value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) started = gatt.writeDescriptor(config, value) == 0;
        else started = config.setValue(value) && gatt.writeDescriptor(config);
        if (!started) { fail("Android could not start the weight subscription. Try again."); return; }
        timeout(9000, "The scale took too long to enable weight indications. Try again.");
    }

    private boolean matches(BluetoothGattCharacteristic received) {
        if (target == null || received == null || received.getService() == null) return false;
        return MEASUREMENT.equals(received.getUuid()) && SERVICE.equals(received.getService().getUuid())
                && received.getInstanceId() == target.getInstanceId()
                && received.getService().getInstanceId() == target.getService().getInstanceId();
    }

    private void receive(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] bytes) {
        // Older Android stacks can mutate the value before the event reaches the UI thread.
        byte[] copy = bytes == null || bytes.length > 15 ? null : bytes.clone();
        main.post(() -> {
            if (gatt != active || !matches(c) || (phase != Phase.SUBSCRIBING && phase != Phase.LISTENING)) return;
            // An indication can arrive before the OS reports the descriptor-write completion.
            SigWeight.Reading reading = SigWeight.decode(copy, TimeZone.getDefault());
            if (reading == null) { invalidReceived = true; return; }
            invalidReceived = false;
            // A scale may send its oldest buffered record first. The session owner deduplicates and
            // selects a fresh result or offers confirmation, then closes this connection on save.
            listener.onGattReading(reading);
        });
    }

    private void timeout(long milliseconds, String message) {
        cancelTimeout();
        BluetoothGatt connection = active;
        deadline = () -> {
            if (connection == active) fail(invalidReceived ? "The scale sent an incomplete or unsuccessful measurement. Weigh again." : message);
        };
        main.postDelayed(deadline, milliseconds);
    }

    private void cancelTimeout() {
        if (deadline != null) main.removeCallbacks(deadline);
        deadline = null;
    }

    private void bluetoothError(RuntimeException error) {
        fail(error instanceof SecurityException ? "Bluetooth access is missing. Allow Nearby devices and try again."
                : "Android could not complete the Bluetooth operation. Check Bluetooth and try again.");
    }

    private void fail(String message) { stop(); listener.onGattError(message); }
}
