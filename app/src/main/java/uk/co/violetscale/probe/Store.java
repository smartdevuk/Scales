package uk.co.violetscale.probe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/** App-private history and preferences. All timestamps are UTC epoch milliseconds. */
public final class Store {
    private static Database shared;
    private final Database helper;
    static final String[] COLUMNS = {"id", "time_ms", "received_ms", "zone_id", "offset_min",
            "weight_kg", "waist_cm", "note", "source", "weight_source", "waist_source",
            "device", "decoder", "original_value", "original_unit", "original_resolution", "imported_ms",
            "modified_ms", "height_cm_at_entry", "impedance_ohms"};

    public Store(Context context) {
        synchronized (Store.class) {
            if (shared == null) shared = new Database(context.getApplicationContext());
            helper = shared;
        }
    }

    public static final class Entry {
        public final String id, zoneId, note, source, weightSource, waistSource, device, decoder;
        public final String originalValue, originalUnit, originalResolution;
        public final long timeMs, receivedMs, importedMs, modifiedMs;
        public final int offsetMinutes;
        public final Double kg, waistCm, heightCmAtEntry, impedanceOhms;

        Entry(String id, long timeMs, long receivedMs, String zoneId, int offsetMinutes,
              Double kg, Double waistCm, String note, String source, String weightSource,
              String waistSource, String device, String decoder, String originalValue,
              String originalUnit, String originalResolution, long importedMs, long modifiedMs, Double heightCmAtEntry,
              Double impedanceOhms) {
            this.id = required(id, 160, "Record ID");
            this.timeMs = timestamp(timeMs);
            this.receivedMs = timestamp(receivedMs);
            this.zoneId = required(zoneId, 128, "Time zone");
            if (Math.abs((long) offsetMinutes) > 18 * 60) throw invalid("Time zone offset");
            this.offsetMinutes = offsetMinutes;
            this.kg = measurement(kg, "Weight");
            this.waistCm = measurement(waistCm, "Waist");
            if (kg == null && waistCm == null) throw new IllegalArgumentException("Enter a weight or waist measurement.");
            this.note = checked(note, 4096, "Note");
            this.source = origin(source, false);
            this.weightSource = origin(weightSource, kg == null);
            this.waistSource = origin(waistSource, waistCm == null);
            this.device = checked(device, 256, "Device");
            this.decoder = checked(decoder, 128, "Decoder");
            this.originalValue = checked(originalValue, 128, "Original value");
            this.originalUnit = checked(originalUnit, 24, "Original unit");
            this.originalResolution = checked(originalResolution, 24, "Original resolution");
            this.importedMs = importedMs == 0 ? 0 : timestamp(importedMs);
            this.modifiedMs = timestamp(modifiedMs);
            this.heightCmAtEntry = measurement(heightCmAtEntry, "Height");
            if (heightCmAtEntry != null && heightCmAtEntry > 300) throw invalid("Height");
            if (impedanceOhms != null && !(impedanceOhms > 0 && impedanceOhms <= 65535)) throw invalid("Impedance");
            this.impedanceOhms = impedanceOhms;
        }

    }

    private static String checked(String value, int limit, String field) {
        if (value == null) return "";
        if (value.length() > limit || value.indexOf(0) >= 0) throw invalid(field);
        return value;
    }
    private static String required(String value, int limit, String field) {
        if (checked(value, limit, field).trim().isEmpty()) throw invalid(field);
        return value;
    }
    static String prefKey(String key) { return required(key, 256, "Setting name"); }
    static String prefValue(String value) { return checked(value, 1024 * 1024, "Setting value"); }
    private static long timestamp(long value) {
        if (value <= 0 || value > 253402300799999L) throw invalid("Timestamp");
        return value;
    }
    private static Double measurement(Double value, String name) {
        if (value != null && !(value > 0 && value <= 1000)) throw invalid(name);
        return value;
    }
    private static String origin(String value, boolean absent) {
        if (absent) return "";
        if ("manual".equals(value) || "scale".equals(value) || "imported".equals(value)) return value;
        throw invalid("Measurement source");
    }
    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException(field + " is invalid.");
    }

    public List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = rows(null, null, null)) { while (c.moveToNext()) out.add(entry(c)); }
        return out;
    }
    public Entry latestWeight() {
        try (Cursor c = rows("weight_kg IS NOT NULL", null, "1")) {
            return c.moveToFirst() ? entry(c) : null;
        }
    }
    public List<Long> weightTimesSince(long startInclusive,long endInclusive) {
        List<Long> times = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query("entries",new String[]{"time_ms"},
                "weight_kg IS NOT NULL AND time_ms BETWEEN ? AND ?",
                new String[]{String.valueOf(startInclusive),String.valueOf(endInclusive)},null,null,"time_ms ASC")) {
            while (c.moveToNext()) times.add(c.getLong(0));
        }
        return times;
    }
    public Entry find(String id) {
        try (Cursor c = rows("id=?", new String[]{id}, "1")) {
            return c.moveToFirst() ? entry(c) : null;
        }
    }
    private Cursor rows(String selection, String[] args, String limit) {
        return helper.getReadableDatabase().query("entries", COLUMNS, selection, args, null, null,
                "time_ms DESC, received_ms DESC, id ASC", limit);
    }
    private static Entry entry(Cursor c) {
        return new Entry(c.getString(0),c.getLong(1),c.getLong(2),c.getString(3),c.getInt(4),
                c.isNull(5) ? null : c.getDouble(5),c.isNull(6) ? null : c.getDouble(6),
                c.getString(7),c.getString(8),c.getString(9),c.getString(10),c.getString(11),
                c.getString(12),c.getString(13),c.getString(14),c.getString(15),c.getLong(16),
                c.getLong(17),c.isNull(18) ? null : c.getDouble(18),c.isNull(19) ? null : c.getDouble(19));
    }
    private static ContentValues values(Entry e) {
        ContentValues v = new ContentValues();
        v.put("id",e.id); v.put("time_ms",e.timeMs); v.put("received_ms",e.receivedMs);
        v.put("zone_id",e.zoneId); v.put("offset_min",e.offsetMinutes); v.put("weight_kg",e.kg);
        v.put("waist_cm",e.waistCm); v.put("note",e.note); v.put("source",e.source);
        v.put("weight_source",e.weightSource); v.put("waist_source",e.waistSource);
        v.put("device",e.device); v.put("decoder",e.decoder); v.put("original_value",e.originalValue);
        v.put("original_unit",e.originalUnit); v.put("original_resolution",e.originalResolution);
        v.put("imported_ms",e.importedMs);
        v.put("modified_ms",e.modifiedMs); v.put("height_cm_at_entry",e.heightCmAtEntry);
        v.put("impedance_ohms",e.impedanceOhms);
        return v;
    }
    private Entry newEntry(String id,long ms,long receivedMs,Double kg,Double waist,String note,String source,
                           String device,String decoder,String raw,String unit,String resolution,Double impedanceOhms) {
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        Double height = null;
        try {
            double cm = Double.parseDouble(get("heightCm",""));
            if (cm > 0 && cm <= 300) height = cm;
        } catch (NumberFormatException ignored) { /* Height is optional. */ }
        return new Entry(id, ms, receivedMs, tz.getID(), tz.getOffset(ms)/60000,
                kg, waist, note, source, source, "manual", device, decoder, raw, unit, resolution, 0, now, height,
                impedanceOhms);
    }
    public Entry saveManual(long ms, Double kg, Double waist, String note) {
        return saveManual(ms, kg, waist, note, kg == null ? "" : kg.toString(), kg == null ? "" : "kg");
    }
    public Entry saveManual(long ms, Double kg, Double waist, String note, String originalValue, String originalUnit) {
        Entry e = newEntry(UUID.randomUUID().toString(), ms, System.currentTimeMillis(), kg, waist, note,
                "manual", "", "", originalValue, originalUnit, "", null);
        helper.getWritableDatabase().insertOrThrow("entries", null, values(e));
        return e;
    }
    public Entry saveScale(String sessionId, long ms, double kg, String device, String decoder) {
        return saveScale(sessionId, ms, System.currentTimeMillis(), kg, device, decoder,
                java.math.BigDecimal.valueOf(kg).stripTrailingZeros().toPlainString(), "kg", "0.01", null);
    }
    public Entry saveScale(String sessionId, long measuredMs, long receivedMs, double kg, String device,
                           String decoder, String originalValue, String originalUnit, String originalResolution) {
        return saveScale(sessionId, measuredMs, receivedMs, kg, device, decoder, originalValue,
                originalUnit, originalResolution, null);
    }
    public Entry saveScale(String sessionId, long measuredMs, long receivedMs, double kg, String device,
                           String decoder, String originalValue, String originalUnit, String originalResolution,
                           Double impedanceOhms) {
        Entry e = newEntry(sessionId, measuredMs, receivedMs, kg, null, "", "scale", device, decoder,
                originalValue, originalUnit, originalResolution, impedanceOhms);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            Entry existing = find(sessionId);
            if (existing == null) db.insertOrThrow("entries", null, values(e));
            else if (e.impedanceOhms != null && existing.impedanceOhms == null && "scale".equals(existing.weightSource)
                    && e.device.equals(existing.device) && e.decoder.equals(existing.decoder)
                    && e.kg.equals(existing.kg) && e.originalValue.equals(existing.originalValue)
                    && e.originalUnit.equals(existing.originalUnit)) {
                updateImpedance(db,existing,e.impedanceOhms);
                existing = find(sessionId);
            }
            db.setTransactionSuccessful();
            return existing == null ? e : existing;
        } finally { db.endTransaction(); }
    }
    public Entry edit(String id, long ms, Double kg, Double waist, String note) {
        return edit(id, ms, kg, waist, note, kg == null ? "" : kg.toString(), kg == null ? "" : "kg");
    }
    /** Caller has matched the originating scale, stable timestamp and weight to this session ID. */
    public boolean enrichScaleImpedance(String id,double ohms) {
        if (id == null || !(ohms > 0 && ohms <= 65535)) return false;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            Entry e = find(id);
            boolean changed = false;
            if (e != null && "scale".equals(e.source) && e.impedanceOhms == null) {
                changed = updateImpedance(db,e,ohms);
            }
            db.setTransactionSuccessful();
            return changed;
        } finally { db.endTransaction(); }
    }
    private boolean updateImpedance(SQLiteDatabase db,Entry e,double ohms) {
        ContentValues extra = new ContentValues();
        extra.put("impedance_ohms",ohms);
        extra.put("modified_ms",Math.max(System.currentTimeMillis(),e.modifiedMs+1));
        return db.update("entries",extra,"id=? AND impedance_ohms IS NULL",new String[]{e.id}) == 1;
    }
    public Entry edit(String id, long ms, Double kg, Double waist, String note, String originalValue, String originalUnit) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            Entry old = find(id);
            if (old == null) throw new IllegalArgumentException("This record has been removed.");
            boolean addedWeight = old.originalValue.isEmpty() && kg != null;
            TimeZone tz = TimeZone.getDefault();
            Entry e = new Entry(id,ms,old.receivedMs,ms == old.timeMs ? old.zoneId : tz.getID(),
                    ms == old.timeMs ? old.offsetMinutes : tz.getOffset(ms)/60000,kg,waist,note,old.source,
                    java.util.Objects.equals(kg,old.kg) ? old.weightSource : "manual",
                    java.util.Objects.equals(waist,old.waistCm) ? old.waistSource : "manual",
                    old.device,old.decoder,addedWeight ? originalValue : old.originalValue,
                    addedWeight ? originalUnit : old.originalUnit,old.originalResolution,old.importedMs,
                    Math.max(System.currentTimeMillis(),old.modifiedMs+1),old.heightCmAtEntry,old.impedanceOhms);
            db.update("entries",values(e),"id=?",new String[]{id});
            db.setTransactionSuccessful();
            return e;
        } finally { db.endTransaction(); }
    }
    public void delete(String id) { helper.getWritableDatabase().delete("entries","id=?",new String[]{id}); }
    public void restore(Entry entry) {
        helper.getWritableDatabase().insertWithOnConflict("entries",null,values(entry),SQLiteDatabase.CONFLICT_IGNORE);
    }
    public String get(String key, String fallback) {
        try (Cursor c = helper.getReadableDatabase().query("settings",new String[]{"value"},"key=?",
                new String[]{key},null,null,null)) { return c.moveToFirst() ? c.getString(0) : fallback; }
    }
    public void put(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key",prefKey(key)); v.put("value",prefValue(value));
        helper.getWritableDatabase().insertWithOnConflict("settings",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    Map<String,String> settings() {
        Map<String,String> out = new LinkedHashMap<>();
        try (Cursor c = helper.getReadableDatabase().query("settings",new String[]{"key","value"},
                null,null,null,null,"key ASC")) {
            while (c.moveToNext()) out.put(c.getString(0),c.getString(1));
        }
        return out;
    }
    static final class Snapshot {
        final List<Entry> entries;
        final Map<String,String> settings;
        Snapshot(List<Entry> entries,Map<String,String> settings) { this.entries=entries; this.settings=settings; }
    }
    Snapshot snapshot() {
        SQLiteDatabase db = helper.getReadableDatabase();
        db.beginTransaction();
        try {
            Snapshot snapshot = new Snapshot(all(),settings());
            db.setTransactionSuccessful();
            return snapshot;
        } finally { db.endTransaction(); }
    }
    int merge(List<Entry> entries, Map<String,String> settings, boolean replaceSettings) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            int added = 0;
            for (Entry e : entries) if (find(e.id) == null) {
                db.insertOrThrow("entries",null,values(e));
                added++;
            }
            for (Map.Entry<String,String> s : settings.entrySet()) {
                if (replaceSettings || get(s.getKey(),null) == null) put(s.getKey(),s.getValue());
            }
            db.setTransactionSuccessful();
            return added;
        } finally { db.endTransaction(); }
    }
    private static final class Database extends SQLiteOpenHelper {
        Database(Context c) { super(c,"violet-history.db",null,3); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY NOT NULL, time_ms INTEGER NOT NULL, "
                    + "received_ms INTEGER NOT NULL, zone_id TEXT NOT NULL, offset_min INTEGER NOT NULL, "
                    + "weight_kg REAL, waist_cm REAL, note TEXT NOT NULL, source TEXT NOT NULL, "
                    + "weight_source TEXT NOT NULL, waist_source TEXT NOT NULL, device TEXT NOT NULL, "
                    + "decoder TEXT NOT NULL, original_value TEXT NOT NULL, original_unit TEXT NOT NULL, "
                    + "original_resolution TEXT NOT NULL, imported_ms INTEGER NOT NULL DEFAULT 0, "
                    + "CHECK(weight_kg IS NOT NULL OR waist_cm IS NOT NULL))");
            db.execSQL("CREATE INDEX entries_time ON entries(time_ms DESC)");
            db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
            migrateToV2(db);
            migrateToV3(db);
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
            if (oldVersion >= 1 && oldVersion < newVersion && newVersion <= 3) {
                if (oldVersion == 1) migrateToV2(db);
                if (newVersion == 3) migrateToV3(db);
                return;
            }
            throw new android.database.sqlite.SQLiteException("Unsupported Scales history database version "
                    + oldVersion + " to " + newVersion + ". Your history has been preserved.");
        }
        private static void migrateToV2(SQLiteDatabase db) {
            db.execSQL("ALTER TABLE entries ADD COLUMN modified_ms INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE entries ADD COLUMN height_cm_at_entry REAL");
            db.execSQL("UPDATE entries SET modified_ms=received_ms");
        }
        private static void migrateToV3(SQLiteDatabase db) {
            db.execSQL("ALTER TABLE entries ADD COLUMN impedance_ohms REAL");
        }
    }
}
