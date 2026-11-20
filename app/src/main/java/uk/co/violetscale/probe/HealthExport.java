package uk.co.violetscale.probe;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-initiated, one-way publication of Scales's own weights. No read permission. */
final class HealthExport {
    static final int REQUEST = 612;
    static final String PERMISSION = "android.permission.health.WRITE_WEIGHT";
    private static final AtomicBoolean running = new AtomicBoolean();

    static void open(HomeActivity activity) {
        if (Build.VERSION.SDK_INT < 34 || !Api34.available(activity)) {
            tell(activity, "Health Connect export requires Health Connect on Android 14 or later.");
            return;
        }
        new AlertDialog.Builder(activity).setTitle("Send weights to Health Connect?")
            .setMessage("Send weights recorded on this phone in Scales, including manual entries. This also updates edited weights and removes weights you previously sent and then deleted. Imported records, waist measurements, notes and habits stay in Scales.")
            .setNegativeButton("Cancel", null).setPositiveButton("Continue", (d,w) -> {
                if (activity.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) sync(activity);
                else activity.requestPermissions(new String[]{PERMISSION}, REQUEST);
            }).show();
    }

    static void permissionResult(HomeActivity activity) {
        if (Build.VERSION.SDK_INT >= 34 && activity.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            sync(activity);
        } else tell(activity, "Weight access is off. You can change it in Health Connect permissions.");
    }

    private static void sync(HomeActivity activity) {
        if (!running.compareAndSet(false, true)) { tell(activity, "Weight export is already in progress."); return; }
        Context context = activity.getApplicationContext();
        try { new Api34(context, activity.store.all()).next(); }
        catch (RuntimeException error) { done(context, "Export interrupted. Your weights are saved in Scales. You can retry."); }
    }

    private static void done(Context context, String text) { running.set(false); tell(context, text); }
    private static void tell(Context context, String text) { Toast.makeText(context, text, Toast.LENGTH_LONG).show(); }

    @android.annotation.TargetApi(34)
    private static final class Api34 {
        final Context context;
        final android.health.connect.HealthConnectManager manager;
        final SharedPreferences ledger;
        final List<Store.Entry> writes = new ArrayList<>();
        final List<String> removes;
        final Map<String,Long> versions = new HashMap<>();
        int cursor, removed;

        static boolean available(Context context) {
            return context.getSystemService(android.health.connect.HealthConnectManager.class) != null;
        }

        Api34(Context context, List<Store.Entry> entries) {
            this.context = context;
            manager = context.getSystemService(android.health.connect.HealthConnectManager.class);
            ledger = context.getSharedPreferences("health_export_v1", Context.MODE_PRIVATE);
            Map<String,?> missing = new HashMap<>(ledger.getAll());
            for (Store.Entry entry : entries) {
                if (entry.kg == null) continue;
                missing.remove(entry.id);
                if (entry.importedMs != 0 || !("scale".equals(entry.weightSource) || "manual".equals(entry.weightSource))) continue;
                String[] old = ledger.getString(entry.id, "").split("/", 2);
                if (old.length == 2 && old[1].equals(signature(entry))) continue;
                long previous = old.length == 2 ? Long.parseLong(old[0]) : 0;
                versions.put(entry.id, Math.max(previous + 1, Math.max(System.currentTimeMillis(), entry.modifiedMs)));
                writes.add(entry);
            }
            removes = new ArrayList<>(missing.keySet());
        }

        String signature(Store.Entry e) { return e.timeMs + "," + e.offsetMinutes + "," + e.kg + "," + e.weightSource; }

        android.health.connect.datatypes.WeightRecord record(Store.Entry e) {
            android.health.connect.datatypes.Metadata.Builder meta = new android.health.connect.datatypes.Metadata.Builder()
                .setClientRecordId(e.id).setClientRecordVersion(versions.get(e.id));
            if ("scale".equals(e.weightSource)) {
                meta.setRecordingMethod(android.health.connect.datatypes.Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED);
                meta.setDevice(new android.health.connect.datatypes.Device.Builder()
                    .setType(android.health.connect.datatypes.Device.DEVICE_TYPE_SCALE).setModel(e.device).build());
            } else meta.setRecordingMethod(android.health.connect.datatypes.Metadata.RECORDING_METHOD_MANUAL_ENTRY);
            return new android.health.connect.datatypes.WeightRecord.Builder(meta.build(),
                    java.time.Instant.ofEpochMilli(e.timeMs), android.health.connect.datatypes.units.Mass.fromGrams(e.kg * 1000))
                    .setZoneOffset(java.time.ZoneOffset.ofTotalSeconds(e.offsetMinutes * 60)).build();
        }

        void next() {
            try {
                if (cursor < writes.size()) {
                    final int end = Math.min(cursor + 100, writes.size());
                    List<android.health.connect.datatypes.Record> batch = new ArrayList<>();
                    for (Store.Entry e : writes.subList(cursor, end)) batch.add(record(e));
                    manager.insertRecords(batch, context.getMainExecutor(), new android.os.OutcomeReceiver<android.health.connect.InsertRecordsResponse, android.health.connect.HealthConnectException>() {
                        public void onResult(android.health.connect.InsertRecordsResponse response) {
                            SharedPreferences.Editor editor = ledger.edit();
                            for (Store.Entry e : writes.subList(cursor, end)) editor.putString(e.id, versions.get(e.id) + "/" + signature(e));
                            if (!editor.commit()) { failed(); return; }
                            cursor = end;
                            next();
                        }
                        public void onError(android.health.connect.HealthConnectException e) { failed(); }
                    });
                } else if (removed < removes.size()) {
                    final int end = Math.min(removed + 100, removes.size());
                    List<android.health.connect.RecordIdFilter> batch = new ArrayList<>();
                    for (String id : removes.subList(removed, end)) batch.add(android.health.connect.RecordIdFilter.fromClientRecordId(android.health.connect.datatypes.WeightRecord.class, id));
                    manager.deleteRecords(batch, context.getMainExecutor(), new android.os.OutcomeReceiver<Void, android.health.connect.HealthConnectException>() {
                        public void onResult(Void result) {
                            SharedPreferences.Editor editor = ledger.edit();
                            for (String id : removes.subList(removed, end)) editor.remove(id);
                            if (!editor.commit()) { failed(); return; }
                            removed = end;
                            next();
                        }
                        public void onError(android.health.connect.HealthConnectException e) { failed(); }
                    });
                } else done(context, writes.isEmpty() && removes.isEmpty() ? "Your weights are up to date." :
                    "Health Connect updated · " + writes.size() + " sent, " + removes.size() + " removed.");
            } catch (RuntimeException error) { failed(); }
        }
        void failed() { done(context, "Export interrupted. Check Health Connect permissions and try again."); }
    }
}
