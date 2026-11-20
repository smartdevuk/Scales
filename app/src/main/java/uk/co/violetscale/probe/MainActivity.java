package uk.co.violetscale.probe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;

public final class MainActivity extends Activity implements Radio.Listener, GattProbe.Listener {
    private enum Page { READY, DISCOVER, RECORD, REVIEW }
    private static final int REQUEST_PERMISSIONS = 1, ENABLE_BT = 2, CREATE_JSON = 3;
    private static final int BG = 0xff0c0b13, SURFACE = 0xff181422, LINE = 0xff40364f;
    private static final int WHITE = 0xfff5f1ff, MUTED = 0xffc1baca, LILAC = 0xffc2a6ff, MINT = 0xff8ee0ca;
    private static final int MATCH = -1, WRAP = -2;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, DeviceRow> devices = new LinkedHashMap<>();
    private Radio radio;
    private GattProbe gatt;
    private Capture capture;
    private Page page;
    private boolean scanning, gattTried, finishingCapture, waitingForPermission;
    private boolean settingsNeeded;
    private long started;
    private String status = "", gattStatus = "", selectedName = "", reading = "";
    private Boolean connectable;
    private int broadcasts, values;
    private Uri lastExport;
    private ScrollView scroll;
    private LinearLayout body, rows;
    private TextView stateText, activityText, liveText, radioText, gattText, nearbyText;
    private Button shareButton, gattButton;
    private EditText note;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateLive();
            if (scanning) handler.postDelayed(this, 600);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        capture = new Capture(this);
        radio = new Radio(this, this);
        gatt = new GattProbe(this, this);
        page = capture.hasSession() ? Page.REVIEW : Page.READY;
        if (page == Page.REVIEW) capture.finish();
        if (state != null) {
            String uri = state.getString("export");
            if (uri != null) lastExport = Uri.parse(uri);
            reading = state.getString("reading", "");
        }
        drawPage();
    }

    private void drawPage() {
        stateText = activityText = liveText = radioText = gattText = nearbyText = null;
        shareButton = gattButton = null;
        note = null;
        rows = null;
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        body = column();
        body.setPadding(dp(22), dp(22), dp(22), dp(36));
        body.setFocusableInTouchMode(true);
        scroll.addView(body);
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_violet);
        header.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView brand = text("Scales", 26, WHITE, true);
        brand.setPadding(dp(10), 0, 0, 0);
        header.addView(brand, new LinearLayout.LayoutParams(0, WRAP, 1));
        header.addView(tag("SCALE PROBE", MUTED));
        body.addView(header);
        space(body, 24);

        LinearLayout panel = column();
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff312342, 0xff1d1729, SURFACE});
        background.setCornerRadius(dp(19));
        background.setStroke(dp(1), LINE);
        panel.setBackground(background);
        body.addView(panel, new LinearLayout.LayoutParams(MATCH, WRAP));

        switch (page) {
            case READY:
                panel.addView(tag("BLUETOOTH LE", LILAC));
                title(panel, "Find your scales");
                panel.addView(text("Force-stop OKOK International, keep this phone beside the scales, then start scanning.", 16, MUTED, false));
                button(panel, "Scan for scales", true, this::requestScan);
                radioText = text(status, 15, MINT, false);
                if (!status.isEmpty()) { space(panel, 10); panel.addView(radioText); }
                if (settingsNeeded) button(panel, "Open app settings", false, this::openSettings);
                section("What to look for");
                body.addView(text("Wake the scales after the scan starts. Look for a device that appears or gets a stronger signal as you move the phone closer. Some scales have no Bluetooth name.", 16, MUTED, false));
                break;
            case DISCOVER:
                stateText = tag("", LILAC);
                panel.addView(stateText);
                title(panel, "Choose your scales");
                panel.addView(text("Wake the scales and watch for a new device. Tap it to record. Earlier packets from that device are included.", 16, MUTED, false));
                radioText = text(status, 14, MINT, false);
                space(panel, 10); panel.addView(radioText);
                button(panel, scanning ? "Stop scanning" : "Review scan", false, () -> {
                    if (scanning) radio.stop(); else finishCapture("Scan ended");
                });
                section("Nearby devices");
                nearbyText = text("", 14, MUTED, false);
                body.addView(nearbyText);
                rows = column();
                body.addView(rows);
                for (DeviceRow device : devices.values()) addRow(device);
                break;
            case RECORD:
                stateText = tag("", LILAC);
                panel.addView(stateText);
                title(panel, selectedName);
                panel.addView(text("Step on and let the display settle. Keep Scales open for another 10 seconds.", 16, MUTED, false));
                space(panel, 18);
                activityText = text("", 16, MINT, true);
                panel.addView(activityText);
                liveText = text("", 17, WHITE, true);
                panel.addView(liveText);
                button(panel, "Finish capture", true, () -> finishCapture("Finished by user"));
                section("Bluetooth link");
                radioText = text("", 15, MUTED, false);
                body.addView(radioText);
                gattText = text("", 15, MUTED, false);
                space(body, 8); body.addView(gattText);
                gattButton = button(body, gattTried ? "Retry GATT connection" : "Try GATT connection", false, this::tryGatt);
                button(body, "Choose another device", false, this::changeDevice);
                break;
            case REVIEW:
                panel.addView(tag("LOCAL RECORDING", LILAC));
                title(panel, "Review capture");
                panel.addView(text(capture.signalSummary(), 19, WHITE, true));
                space(panel, 6); panel.addView(text(capture.summary() + " in the dump", 14, MUTED, false));
                if (!reading.isEmpty()) { space(panel, 6); panel.addView(text(reading, 16, MINT, false)); }
                radioText = text(status, 14, MUTED, false);
                space(panel, 8); panel.addView(radioText);
                section("Scale display");
                body.addView(text("Add the reading exactly as shown, including its unit. This lets us check the Bluetooth data against the display.", 15, MUTED, false));
                note = new EditText(this);
                note.setSingleLine(true);
                note.setTextSize(18); note.setTextColor(WHITE); note.setHintTextColor(MUTED);
                note.setHint("e.g. 70.35 kg or 11 st 1 lb");
                note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(240)});
                note.setText(capture.getNote());
                note.setMinHeight(dp(52));
                note.setContentDescription("Reading and unit shown on the scale");
                note.addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        capture.note(s.toString());
                        if (lastExport != null) {
                            lastExport = null;
                            if (shareButton != null) shareButton.setVisibility(View.GONE);
                            status = "Reading changed. Save a new JSON to include it.";
                            updateLive();
                        }
                    }
                    public void afterTextChanged(Editable s) {}
                });
                body.addView(note);
                button(body, "Save JSON dump", true, this::export);
                if (lastExport != null) shareButton = button(body, "Share saved file", false, this::share);
                body.addView(text("The dump includes the selected device’s raw packets, which may contain its Bluetooth identifier, plus your note and phone model.", 13, MUTED, false));
                button(body, "Start another scan", false, () -> new AlertDialog.Builder(this)
                        .setTitle("Start another scan?")
                        .setMessage("A new scan replaces the recording inside Scales. Any JSON file you already saved stays available.")
                        .setNegativeButton("Keep capture", null)
                        .setPositiveButton("Start scan", (d, w) -> requestScan()).show());
                if (settingsNeeded) button(body, "Open app settings", false, this::openSettings);
                break;
        }
        updateLive();
    }

    private void requestScan() {
        String[] permissions = Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                waitingForPermission = true;
                requestPermissions(permissions, REQUEST_PERMISSIONS);
                return;
            }
        }
        checkBluetooth();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != REQUEST_PERMISSIONS) return;
        waitingForPermission = false;
        for (int i = 0; i < permissions.length; i++) {
            if (checkSelfPermission(permissions[i]) != PackageManager.PERMISSION_GRANTED) {
                settingsNeeded = !shouldShowRequestPermissionRationale(permissions[i]);
                status = Build.VERSION.SDK_INT >= 31 ? "Allow Nearby devices to scan." : "Allow location access while using Scales to scan on this Android version.";
                drawPage();
                return;
            }
        }
        if (results.length > 0) checkBluetooth();
    }

    private void checkBluetooth() {
        settingsNeeded = false;
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            if (adapter != null && !adapter.isEnabled()) {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BT);
                return;
            }
            if (Build.VERSION.SDK_INT <= 30 && !locationEnabled()) {
                new AlertDialog.Builder(this).setTitle("Turn on Location")
                        .setMessage("This Android version uses the Location switch for nearby Bluetooth scans. Turn it on, then tap Scan again.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Open settings", (d,w) ->
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).show();
                return;
            }
            beginScan();
        } catch (SecurityException | android.content.ActivityNotFoundException ex) {
            status = "Bluetooth access is unavailable. Check Bluetooth and Nearby devices in Settings.";
            settingsNeeded = true;
            drawPage();
        }
    }

    private boolean locationEnabled() {
        LocationManager location = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (location == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return location.isLocationEnabled();
        return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
    }

    private void beginScan() {
        radio.clearSelection();
        devices.clear();
        if (!radio.start()) { drawPage(); return; }
        scanning = true;
        capture.begin(null);
        started = SystemClock.elapsedRealtime();
        broadcasts = values = 0;
        reading = ""; gattStatus = ""; gattTried = false; lastExport = null;
        page = Page.DISCOVER;
        logStatus("scan_started", status);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        drawPage();
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 600);
    }

    @Override public void onDevice(String address, String name, int rssi, Boolean canConnect) {
        if (page == Page.RECORD && address.equals(radio.selectedAddress())) {
            connectable = canConnect;
            updateLive();
        }
        if (page != Page.DISCOVER) return;
        DeviceRow device = devices.get(address);
        if (device == null) {
            if (devices.size() >= 64) {
                DeviceRow oldest = null;
                for (DeviceRow item : devices.values()) if (oldest == null || item.seen < oldest.seen) oldest = item;
                if (oldest != null) { devices.remove(oldest.address); if (rows != null) rows.removeView(oldest.view); }
            }
            device = new DeviceRow(address);
            devices.put(address, device);
        }
        device.rssi = rssi; device.connectable = canConnect; device.seen = SystemClock.elapsedRealtime();
        device.clue = radio.clue(address);
        if (name != null && !name.trim().isEmpty()) device.name = name;
        if (device.view == null) addRow(device);
        updateRow(device);
        updateLive();
    }

    private void choose(DeviceRow device) {
        selectedName = device.displayName();
        connectable = device.connectable;
        page = Page.RECORD;
        capture.begin(selectedName);
        if (!radio.select(device.address)) {
            page = Page.DISCOVER;
            drawPage();
            return;
        }
        logStatus("device_selected", selectedName);
        status = "Listening to broadcasts from the selected device.";
        if (!scanning) { finishCapture("Saved the selected device’s buffered packets."); return; }
        drawPage();
    }

    private void changeDevice() {
        new AlertDialog.Builder(this).setTitle("Choose another device?")
                .setMessage("This drops the current device’s recording. To keep it, finish and save it first.")
                .setNegativeButton("Stay here", null)
                .setPositiveButton("Choose device", (d,w) -> {
                    gatt.stop();
                    radio.clearSelection();
                    capture.begin(null);
                    page = Page.DISCOVER;
                    broadcasts = values = 0;
                    gattStatus = reading = "";
                    gattTried = false;
                    status = "Wake the scales and select a device.";
                    drawPage();
                }).show();
    }

    private void tryGatt() {
        if (radio.selectedDevice() == null || !scanning) return;
        gattTried = true;
        gatt.start(radio.selectedDevice());
        drawPage();
    }

    @Override public void onPacket(JSONObject packet) {
        if (page != Page.RECORD) return;
        broadcasts++;
        record(packet);
    }
    @Override public void onGattEvent(JSONObject event) {
        if (page != Page.RECORD) return;
        String type = event.optString("type");
        if ("gatt_read".equals(type) || "gatt_notification".equals(type)) values++;
        record(event);
    }
    private void record(JSONObject event) {
        String value = Decoder.describe(event);
        if (value != null) reading = value;
        capture.add(event);
    }
    @Override public void onGattStatus(String text) { gattStatus = text; updateLive(); }
    @Override public void onRadioStatus(String text) { status = text; updateLive(); }
    @Override public void onScanEnded() {
        scanning = false;
        handler.removeCallbacks(refresh);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (finishingCapture) return;
        if (page == Page.DISCOVER && !devices.isEmpty()) {
            logStatus("scan_ended", status);
            capture.finish();
            drawPage(); // Keep buffered devices available for selection after timeout.
        } else if (page == Page.RECORD || page == Page.DISCOVER) {
            finishCapture(status);
        }
    }

    private void finishCapture(String reason) {
        if (finishingCapture) return;
        finishingCapture = true;
        gatt.stop();
        radio.stop();
        scanning = false;
        logStatus("capture_ended", reason);
        capture.finish();
        page = Page.REVIEW;
        status = reason;
        finishingCapture = false;
        drawPage();
    }

    private void logStatus(String type, String message) {
        try {
            capture.add(new JSONObject().put("type", type).put("timestampMs", System.currentTimeMillis())
                    .put("message", message).put("nearbyDeviceCount", devices.size()));
        } catch (JSONException ignored) { }
    }

    private void updateLive() {
        if (stateText != null) {
            long seconds = Math.min(120, (SystemClock.elapsedRealtime() - started) / 1000);
            String label = (page == Page.RECORD ? "RECORDING" : (scanning ? "SCANNING" : "SCAN ENDED"));
            stateText.setText(label + "   /   " + String.format(Locale.UK, "%d:%02d", seconds / 60, seconds % 60));
        }
        if (nearbyText != null) nearbyText.setText(devices.isEmpty() ? "Waiting for the first advertisement…" : devices.size() + (devices.size() == 1 ? " device seen" : " devices seen"));
        if (radioText != null) radioText.setText(status);
        if (activityText != null) activityText.setText(broadcasts + (broadcasts == 1 ? " broadcast" : " broadcasts") + "   ·   " + values + (values == 1 ? " GATT value" : " GATT values"));
        if (liveText != null) { liveText.setText(reading); liveText.setVisibility(reading.isEmpty() ? View.GONE : View.VISIBLE); }
        boolean offerGatt = !Boolean.FALSE.equals(connectable) || gattTried;
        if (gattButton != null) gattButton.setVisibility(offerGatt ? View.VISIBLE : View.GONE);
        if (gattText != null) gattText.setText(!gattStatus.isEmpty() ? gattStatus : offerGatt
                ? "Optional: connect to listen for measurement notifications."
                : "This advertisement is broadcast only. Scales can record it directly.");
    }

    private void export() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "scales-capture-" + stamp + ".json");
        try { startActivityForResult(intent, CREATE_JSON); }
        catch (android.content.ActivityNotFoundException ex) { toast("No document picker available on this phone."); }
    }
    private void share() {
        if (lastExport == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND).setType("application/json")
                .putExtra(Intent.EXTRA_STREAM, lastExport).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Scales Bluetooth capture", lastExport));
        try { startActivity(Intent.createChooser(intent, "Share your scale capture")); }
        catch (RuntimeException ex) { toast("Attach the saved JSON file from your Files app."); }
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == ENABLE_BT) {
            if (result == RESULT_OK) checkBluetooth();
            else { status = "Turn on Bluetooth to scan."; drawPage(); }
        } else if (request == CREATE_JSON && result == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri uri = data.getData();
                capture.save(uri);
                lastExport = uri;
                status = "JSON saved. Attach the file to this chat or use Share saved file.";
                drawPage();
            } catch (IOException | RuntimeException ex) {
                status = "Couldn’t save the JSON. Your capture is still here. Try saving in Downloads.";
                drawPage();
            }
        }
    }
    private void openSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }
    @Override protected void onStop() {
        super.onStop();
        if (!waitingForPermission && (scanning || page == Page.DISCOVER || page == Page.RECORD))
            finishCapture("Capture stopped when Scales left the foreground.");
        else if (page == Page.REVIEW) capture.finish();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        gatt.stop(); radio.stop();
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (scanning) { finishCapture("Finished by user"); return; }
        super.onBackPressed();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (lastExport != null) out.putString("export", lastExport.toString());
        out.putString("reading", reading);
    }

    private static final class DeviceRow {
        final String address;
        String name, clue;
        int rssi;
        long seen;
        Boolean connectable;
        Button view;
        DeviceRow(String address) { this.address = address; }
        String displayName() { return name == null ? "Unnamed device" : name; }
    }
    private void addRow(DeviceRow device) {
        if (rows == null) return;
        device.view = button(rows, "", false, () -> choose(device));
        device.view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        updateRow(device);
    }
    private void updateRow(DeviceRow device) {
        if (device.view == null) return;
        String suffix = device.address.substring(Math.max(0, device.address.length() - 5));
        device.view.setText(device.displayName() + "    ·    " + suffix + "\n" + device.rssi + " dBm" +
                (Boolean.FALSE.equals(device.connectable) ? "   ·   broadcast only" : "") +
                (device.clue == null ? "" : "\n" + device.clue));
    }
    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value + 0.5f); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(color); t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setIncludeFontPadding(false); t.setLineSpacing(dp(2), 1f); return t;
    }
    private TextView tag(String s, int color) {
        TextView t = text(s, 12, color, true); t.setTypeface(Typeface.MONOSPACE); t.setLetterSpacing(0.08f); return t;
    }
    private void space(LinearLayout parent, int height) { parent.addView(new View(this), 1, dp(height)); }
    private void title(LinearLayout target, String s) {
        space(target, 9); target.addView(text(s, 32, WHITE, true)); space(target, 10);
    }
    private void section(String s) { space(body, 23); body.addView(text(s, 21, WHITE, true)); space(body, 8); }
    private Button button(LinearLayout target, String s, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(16);
        b.setMinHeight(dp(49)); b.setMinWidth(0); b.setPadding(dp(13), dp(10), dp(13), dp(10));
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? BG : WHITE);
        GradientDrawable base = new GradientDrawable(); base.setCornerRadius(dp(12));
        base.setColor(primary ? LILAC : SURFACE);
        if (!primary) base.setStroke(dp(1), LINE);
        b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x449781bd), base, null));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP); lp.topMargin = dp(9); lp.bottomMargin = dp(4);
        target.addView(b, lp); return b;
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
