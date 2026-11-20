package uk.co.violetscale.probe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

final class TransferCodec {
    private static final String FORMAT = "uk.co.violetscale.backup";
    private static final String[] FIELDS = {"id","date_time","weight_kg","waist_cm","note","source",
            "weight_source","waist_source","device","decoder","original_value","original_unit",
            "original_resolution","time_ms","received_ms","zone_id","offset_min","imported_ms","modified_ms",
            "height_cm_at_entry","impedance_ohms"};
    // Every field in the original exported format. Later nullable columns remain optional when importing it.
    private static final List<String> V1_CSV_FIELDS = Arrays.asList(FIELDS).subList(0,20);
    private final SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.ROOT);
    private final SimpleDateFormat seconds = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.ROOT);
    TransferCodec() { date.setLenient(false); seconds.setLenient(false); }

    static final class Preview {
        final List<Store.Entry> entries = new ArrayList<>();
        final Map<String,String> settings = new LinkedHashMap<>();
        final List<String> problems = new ArrayList<>();
        final HashSet<String> ids = new HashSet<>();
        int invalid, duplicates, skippedSettings;
        void add(Store.Entry e) {
            if (ids.add(e.id)) entries.add(e);
            else duplicates++;
        }
        void problem(int n,Exception ex) {
            invalid++;
            if (problems.size() < 4) problems.add("Record " + n + " · " +
                    (ex instanceof NumberFormatException ? "Invalid number." : ex.getMessage()));
        }
    }

    private static String fixedZone(int offsetMinutes) {
        int m = Math.abs(offsetMinutes);
        return String.format(Locale.ROOT,"GMT%s%02d:%02d",offsetMinutes < 0 ? "-" : "+",m/60,m%60);
    }
    private Object[] fields(Store.Entry e) {
        date.setTimeZone(TimeZone.getTimeZone(fixedZone(e.offsetMinutes)));
        return new Object[]{e.id,date.format(new Date(e.timeMs)),e.kg,e.waistCm,e.note,e.source,
                e.weightSource,e.waistSource,e.device,e.decoder,e.originalValue,e.originalUnit,
                e.originalResolution,e.timeMs,e.receivedMs,e.zoneId,e.offsetMinutes,e.importedMs,
                e.modifiedMs,e.heightCmAtEntry,e.impedanceOhms};
    }
    String csv(List<Store.Entry> entries) {
        StringBuilder out = new StringBuilder();
        DataCsv.row(out,Arrays.asList(FIELDS));
        for (Store.Entry e : entries) {
            List<String> cells = new ArrayList<>();
            for (Object item : fields(e)) {
                cells.add(item instanceof String ? DataCsv.protectText((String)item) :
                        item == null ? "" : String.valueOf(item));
            }
            DataCsv.row(out,cells);
        }
        return out.toString();
    }
    String backup(Store.Snapshot snapshot) throws JSONException {
        JSONObject root = new JSONObject().put("format",FORMAT).put("version",1);
        root.put("exported_ms",System.currentTimeMillis());
        JSONArray history = new JSONArray();
        for (Store.Entry e : snapshot.entries) {
            JSONObject o = new JSONObject();
            Object[] fields = fields(e);
            for (int i=0;i<FIELDS.length;i++) o.put(FIELDS[i],fields[i] == null ? JSONObject.NULL : fields[i]);
            history.put(o);
        }
        JSONObject settings = new JSONObject();
        for (Map.Entry<String,String> pref : snapshot.settings.entrySet()) {
            if (allowedSetting(pref.getKey())) settings.put(pref.getKey(),pref.getValue());
        }
        return root.put("records",history).put("settings",settings).toString();
    }
    private static boolean allowedSetting(String key) {
        return Arrays.asList("unit","heightCm","goalMode","targetKg","rangeLowKg","rangeHighKg",
                "habits.selection.v1","habits.weeklyWeighDays","habits.reminder.enabled","habits.reminder.minute")
                .contains(key) || key.matches("habits\\.check\\.[a-zA-Z0-9_.-]{1,120}\\.\\d{4}-\\d{2}-\\d{2}");
    }
    private static boolean validSetting(String key, String value) {
        if (!allowedSetting(key)) return false;
        try {
            Store.prefValue(value);
            if (key.equals("unit")) return Arrays.asList("kg","lb","st").contains(value);
            if (key.startsWith("habits.check.") || key.equals("habits.reminder.enabled"))
                return value.isEmpty() || value.equals("0") || value.equals("1");
            if (key.equals("habits.weeklyWeighDays") || key.equals("habits.reminder.minute")) {
                int n = Integer.parseInt(value);
                return n >= 0 && n <= (key.endsWith("minute") ? 1439 : 7);
            }
            if (key.equals("habits.selection.v1")) return Habits.validSelection(value);
            if (key.equals("goalMode")) return Arrays.asList("track","maintain","gain","lose").contains(value);
            if (value.isEmpty()) return true;
            double n = Double.parseDouble(value);
            return n > 0 && n <= (key.equals("heightCm") ? 300 : 1000);
        } catch (Exception ex) { return false; }
    }
    Preview parseBackup(String contents) throws JSONException {
        JSONObject root = new JSONObject(contents);
        if (!FORMAT.equals(root.optString("format")) || root.getInt("version") != 1)
            throw new IllegalArgumentException("Choose a Scales JSON backup (format version 1).");
        JSONArray records = root.getJSONArray("records");
        if (records.length() > DataCsv.MAX_ROWS) throw new IllegalArgumentException("The backup exceeds 50,000 records.");
        Preview p = new Preview();
        for (int i=0;i<records.length();i++) {
            try {
                JSONObject o = records.getJSONObject(i);
                Map<String,String> fields = new HashMap<>();
                for (String key : FIELDS) if (!o.isNull(key)) fields.put(key,o.getString(key));
                if (!fields.containsKey("id") || !fields.containsKey("time_ms"))
                    throw new IllegalArgumentException("Missing ID or timestamp.");
                p.add(record(fields,false));
            } catch (IllegalArgumentException | JSONException ex) { p.problem(i+1,ex); }
        }
        JSONObject prefs = root.getJSONObject("settings");
        Iterator<String> it = prefs.keys();
        while (it.hasNext()) {
            String key = it.next();
            Object value = prefs.get(key);
            if (value instanceof String && validSetting(key,(String)value)) p.settings.put(key,(String)value);
            else p.skippedSettings++;
        }
        return p;
    }
    Preview parseCsv(String contents) {
        Preview p = new Preview();
        List<String> header = new ArrayList<>();
        DataCsv.read(contents,(cells,n) -> {
            if (header.isEmpty()) {
                for (String h : cells) header.add(h.trim().toLowerCase(Locale.ROOT));
                if (new HashSet<>(header).size() != header.size() ||
                        !(header.contains("date_time") || header.contains("time_ms")) ||
                        !(header.contains("weight_kg") || header.contains("weight_lb") || header.contains("waist_cm")))
                    throw new IllegalArgumentException("CSV needs date_time or time_ms and a weight_kg, weight_lb or waist_cm column.");
                return;
            }
            if (cells.size() == 1 && cells.get(0).isEmpty()) return;
            try {
                if (header.size() != cells.size()) throw new IllegalArgumentException("Wrong number of columns.");
                Map<String,String> values = new HashMap<>();
                for (int i=0;i<header.size();i++) values.put(header.get(i),cells.get(i));
                // Escaped text belongs to Scales exports only; preserve arbitrary third-party notes verbatim.
                if (header.containsAll(V1_CSV_FIELDS)) {
                    for (String key : Arrays.asList("id","note","device","decoder","original_value","original_unit",
                            "original_resolution","zone_id")) values.put(key,DataCsv.unprotectText(value(values,key,"")));
                } else {
                    values.put("source","imported");
                    values.put("weight_source","imported");
                    values.put("waist_source","imported");
                    values.remove("impedance_ohms");
                }
                p.add(record(values,true));
            } catch (IllegalArgumentException ex) { p.problem(n,ex); }
        });
        if (header.isEmpty()) throw new IllegalArgumentException("The CSV is empty.");
        return p;
    }
    private static String value(Map<String,String> f,String key,String fallback) {
        String s = f.get(key);
        return s == null || s.isEmpty() ? fallback : s;
    }
    private static Double decimal(Map<String,String> f,String key) {
        String s = value(f,key,"").trim();
        return s.isEmpty() ? null : Double.valueOf(s);
    }
    private static long integer(Map<String,String> f,String key,long fallback) {
        String s = value(f,key,"").trim();
        return s.isEmpty() ? fallback : Long.parseLong(s);
    }
    private long parseDate(String iso) {
        for (SimpleDateFormat fmt : Arrays.asList(date,seconds)) {
            ParsePosition pos = new ParsePosition(0);
            Date when = fmt.parse(iso,pos);
            if (when != null && pos.getIndex() == iso.length()) return when.getTime();
        }
        throw new IllegalArgumentException("Use an ISO date and time with a UTC offset.");
    }
    private Store.Entry record(Map<String,String> f, boolean csv) {
        String iso = value(f,"date_time","").trim();
        long ms = integer(f,"time_ms",0);
        if (ms == 0) ms = parseDate(iso);
        else if (!iso.isEmpty() && parseDate(iso) != ms) throw new IllegalArgumentException("The two timestamp columns disagree.");
        int offset = 0;
        if (iso.matches(".*[+-]\\d{2}:\\d{2}$")) {
            String suffix = iso.substring(iso.length()-6);
            offset = (Integer.parseInt(suffix.substring(1,3))*60+Integer.parseInt(suffix.substring(4,6))) *
                    (suffix.charAt(0) == '-' ? -1 : 1);
        }
        offset = Integer.parseInt(value(f,"offset_min",String.valueOf(offset)));
        Double kg = decimal(f,"weight_kg");
        Double lb = decimal(f,"weight_lb");
        if (lb != null && kg != null) throw new IllegalArgumentException("Enter one weight unit per row.");
        if (lb != null) kg = lb * 0.45359237;
        Double waist = decimal(f,"waist_cm");
        String note = value(f,"note","");
        String id = value(f,"id","");
        if (id.isEmpty()) {
            String normalized = ms + "|" + kg + "|" + waist + "|" + note;
            id = "csv-" + UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8));
        }
        String source = value(f,"source","imported");
        long received = integer(f,"received_ms",ms);
        return new Store.Entry(id,ms,received,value(f,"zone_id",fixedZone(offset)),offset,kg,waist,note,source,
                value(f,"weight_source",source),value(f,"waist_source",source),value(f,"device",""),
                value(f,"decoder",""),value(f,"original_value",kg == null ? "" : value(f,lb == null ? "weight_kg" : "weight_lb","")),
                value(f,"original_unit",kg == null ? "" : (lb == null ? "kg" : "lb")),value(f,"original_resolution",""),
                csv ? System.currentTimeMillis() : integer(f,"imported_ms",0L),integer(f,"modified_ms",received),
                decimal(f,"height_cm_at_entry"),decimal(f,"impedance_ohms"));
    }
}
