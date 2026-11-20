package uk.co.violetscale.probe;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.TimeZone;
import java.util.UUID;

/** One local diagnostic session, shared across activity recreation. Call on the main thread. */
public final class Capture {
    // Preserve discovery and connection setup as well as the end of the weigh-in.
    private static final int OPENING_EVENTS = 128;
    private static final int LAST_EVENTS = 1920;
    private static final int LIMIT = OPENING_EVENTS + LAST_EVENTS;
    private static Store shared;
    private final Store store;

    public Capture(Context context) {
        if (shared == null) shared = new Store(context.getApplicationContext());
        store = shared;
    }

    public void begin(String deviceName) {
        store.begin(deviceName);
    }

    public boolean hasSession() {
        return store.id != null;
    }

    public void add(JSONObject event) {
        if (!hasSession() || event == null) return;
        String body = event.toString();
        if (body == null) return;
        store.pending.add(new Entry(++store.total, System.currentTimeMillis(), body));
        store.signalSummary = null;
        store.finished = 0;
        store.schedule();
    }

    public int count() {
        return (int) Math.min(store.total, LIMIT);
    }

    public String summary() {
        if (!hasSession()) return "No capture yet";
        String countText = count() + (count() == 1 ? " event" : " events");
        long omitted = store.total - count();
        return omitted == 0 ? countText : countText + " kept · " + omitted + " omitted";
    }

    public String signalSummary() {
        if (!hasSession()) return "No capture yet";
        if (store.name == null) return "No device was selected";
        if (store.signalSummary != null) return store.signalSummary;
        if (!store.pending.isEmpty()) store.flush();
        int broadcasts = 0;
        int values = 0;
        try (Cursor rows = store.db.rawQuery("SELECT body FROM events", null)) {
            while (rows.moveToNext()) {
                try {
                    JSONObject event = new JSONObject(rows.getString(0));
                    if ("advertisement".equals(event.optString("kind"))) broadcasts++;
                    String type = event.optString("type");
                    if (("gatt_read".equals(type) || "gatt_notification".equals(type))
                            && event.optInt("status", -1) == 0 && !event.isNull("valueHex")) values++;
                } catch (JSONException ignored) {
                    // An unreadable record is never counted as a successful signal.
                }
            }
        }
        store.signalSummary = store.name + "\n" + broadcasts
                + (broadcasts == 1 ? " broadcast" : " broadcasts") + " · " + values
                + (values == 1 ? " GATT value" : " GATT values");
        return store.signalSummary;
    }

    public String getNote() {
        return store.note;
    }

    public void note(String text) {
        if (!hasSession()) return;
        String value = text == null ? "" : text;
        if (!value.equals(store.note)) {
            store.note = value.length() > 512 ? value.substring(0, 512) : value;
            store.schedule();
        }
    }

    public void finish() {
        if (!hasSession()) return;
        if (store.finished == 0) store.finished = System.currentTimeMillis();
        store.flush();
    }

    public void save(Uri destination) throws IOException {
        if (!hasSession()) throw new IOException("Start a capture first.");
        store.flush();
        JSONObject meta = new JSONObject();
        try {
            PackageInfo app = store.context.getPackageManager()
                    .getPackageInfo(store.context.getPackageName(), 0);
            meta.put("schema", "uk.co.violetscale.probe.capture");
            meta.put("schemaVersion", 1);
            meta.put("appVersion", app.versionName);
            meta.put("appVersionCode", app.versionCode);
            meta.put("decoderVersion", Decoder.VERSION);
            meta.put("captureId", store.id);
            meta.put("phoneManufacturer", Build.MANUFACTURER);
            meta.put("phoneModel", Build.MODEL);
            meta.put("androidApi", Build.VERSION.SDK_INT);
            meta.put("sessionTimeZone", store.timeZone);
            meta.put("sessionStartedUnixMs", store.started);
            meta.put("sessionFinishedUnixMs", store.finished == 0 ? JSONObject.NULL : store.finished);
            meta.put("exportedUnixMs", System.currentTimeMillis());
            meta.put("selectedDeviceName", store.name == null ? JSONObject.NULL : store.name);
            meta.put("displayedValueNote", store.note);
            meta.put("eventsObserved", store.total);
            meta.put("eventsRetained", count());
            meta.put("eventsOmitted", store.total - count());
            meta.put("retention", "First 128 and most recent 1920 events when full");
            meta.put("privacy", "Selected-device bytes may contain embedded identifiers and weight data.");
        } catch (PackageManager.NameNotFoundException | JSONException e) {
            throw new IOException("Could not create capture details.", e);
        }
        OutputStream stream = store.context.getContentResolver().openOutputStream(destination, "wt");
        if (stream == null) throw new IOException("The chosen destination could not be opened.");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
             Cursor rows = store.db.rawQuery("SELECT seq, recorded, body FROM events ORDER BY seq", null)) {
            writer.write("{\n  \"metadata\": ");
            writer.write(meta.toString());
            writer.write(",\n  \"events\": [");
            while (rows.moveToNext()) {
                if (!rows.isFirst()) writer.write(",");
                writer.write("\n    {\"sequence\":");
                writer.write(Long.toString(rows.getLong(0)));
                writer.write(",\"recordedUnixMs\":");
                writer.write(Long.toString(rows.getLong(1)));
                writer.write(",\"event\":");
                writer.write(rows.getString(2));
                writer.write("}");
            }
            writer.write("\n  ]\n}\n");
        }
    }

    private static final class Entry {
        final long sequence;
        final long recorded;
        final String body;
        Entry(long sequence, long recorded, String body) {
            this.sequence = sequence;
            this.recorded = recorded;
            this.body = body;
        }
    }

    private static final class Store {
        final Context context;
        final SQLiteDatabase db;
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable checkpoint = this::flush;
        final ArrayList<Entry> pending = new ArrayList<>();
        boolean scheduled;
        String id, name, timeZone, signalSummary;
        String note = "";
        long started, finished, total;

        Store(Context context) {
            this.context = context;
            db = new CaptureDatabase(context).getWritableDatabase();
            try (Cursor row = db.rawQuery(
                    "SELECT id, name, zone, started, finished, note, total FROM session", null)) {
                if (row.moveToFirst()) {
                    id = row.getString(0);
                    name = row.getString(1);
                    timeZone = row.getString(2);
                    started = row.getLong(3);
                    finished = row.getLong(4);
                    note = row.getString(5);
                    total = row.getLong(6);
                }
            }
        }

        void begin(String deviceName) {
            String nextId = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            String zone = TimeZone.getDefault().getID();
            db.beginTransaction();
            try {
                db.delete("events", null, null);
                db.delete("session", null, null);
                db.execSQL("INSERT INTO session (id,name,zone,started,finished,note,total) "
                                + "VALUES(?,?,?,?,0,'',0)",
                        new Object[]{nextId, deviceName, zone, now});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            main.removeCallbacks(checkpoint);
            scheduled = false;
            pending.clear();
            id = nextId;
            started = now;
            timeZone = zone;
            name = deviceName;
            signalSummary = null;
            finished = 0;
            total = 0;
            note = "";
        }

        void schedule() {
            if (pending.size() >= 32) {
                flush();
            } else if (!scheduled) {
                scheduled = true;
                main.postDelayed(checkpoint, 250);
            }
        }

        void flush() {
            main.removeCallbacks(checkpoint);
            scheduled = false;
            db.beginTransaction();
            try {
                for (Entry e : pending) {
                    db.execSQL("INSERT INTO events(seq,recorded,body) VALUES(?,?,?)",
                            new Object[]{e.sequence, e.recorded, e.body});
                }
                if (total > LIMIT && !pending.isEmpty()) {
                    db.delete("events", "seq > ? AND seq <= ?", new String[]{
                            Integer.toString(OPENING_EVENTS), Long.toString(total - LAST_EVENTS)});
                }
                ContentValues fields = new ContentValues();
                fields.put("note", note);
                fields.put("finished", finished);
                fields.put("total", total);
                db.update("session", fields, null, null);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            pending.clear();
        }
    }

    private static final class CaptureDatabase extends SQLiteOpenHelper {
        CaptureDatabase(Context context) {
            super(context, new File(context.getNoBackupFilesDir(), "probe-capture.sqlite")
                    .getAbsolutePath(), null, 1);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE session (id TEXT PRIMARY KEY, name TEXT, zone TEXT NOT NULL, "
                    + "started INTEGER NOT NULL, finished INTEGER NOT NULL, "
                    + "note TEXT NOT NULL, total INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE events (seq INTEGER PRIMARY KEY, recorded INTEGER NOT NULL,"
                    + " body TEXT NOT NULL)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unknown capture database version " + oldVersion);
        }
    }
}
