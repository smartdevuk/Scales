package uk.co.violetscale.probe;

import org.json.JSONObject;
import java.util.Collections;
import java.util.Map;

/** Run against a fresh, isolated database. Includes repeat merging and portable CSV/JSON tests. */
public final class DataFixtures {
    private static void ok(boolean condition,String name) {
        if (!condition) throw new AssertionError(name);
    }
    private static void near(double a,double b,String name) { ok(Math.abs(a-b)<1e-9,name); }
    public static void run(Store s) throws Exception {
        long when = 1735732800000L;
        s.put("heightCm","172.0"); s.put("unit","st"); s.put("goalMode","track");
        s.put("habits.weeklyWeighDays","3"); s.put("local.reminder.lastDay","2025-01-01");
        Store.Entry a = s.saveScale("session-A",when,64.35,"Fixture scale","test-1");
        ok("session-A".equals(a.id) && a.waistCm == null,"scale saved");
        near(a.heightCmAtEntry,172.0,"height snapshot");
        s.put("heightCm","180"); near(s.find(a.id).heightCmAtEntry,172.0,"snapshot immutable");
        ok(s.saveScale(a.id,when+1000,88.0,"Fixture scale","test-1").kg.equals(a.kg),"repeated session");
        long measured = when + 90000, received = measured+4500;
        double measuredKg = 168 * 0.45359237;
        Store.Entry b = s.saveScale("session-B",measured,received,measuredKg,"Broadcast","lb-test",
                "168.00","lb","0.01",null);
        ok(b.originalUnit.equals("lb") && b.originalValue.equals("168.00") && b.receivedMs==received
                && b.timeMs==measured && b.impedanceOhms==null,"protocol origin metadata");
        s.saveScale("session-B",measured,received,measuredKg,"Other scale","lb-test","168.00","lb","0.01",440.0);
        ok(s.find(b.id).impedanceOhms==null,"mismatched late packet ignored");
        b = s.saveScale("session-B",measured,received,measuredKg,"Broadcast","lb-test","168.00","lb","0.01",500.5);
        ok(b.impedanceOhms.equals(500.5) && s.all().size()==2,"linked late impedance enriches session");
        ok(s.find(a.id).impedanceOhms==null,"unknown impedance stays absent");
        String note = "=my note,\r\nwith \"quotes\" and purple 💜";
        Store.Entry edited = s.edit(a.id,when,64.35,86.1,note);
        ok("scale".equals(edited.weightSource) && "manual".equals(edited.waistSource),"per-metric provenance");
        ok(edited.modifiedMs>a.modifiedMs,"edit version monotonic");
        Store.Entry manual = s.saveManual(when+1000,76.3,null,"","12 st 0.2 lb","st");
        Store.Entry waist = s.saveManual(when+2000,null,85.5,"Tape only");
        ok(!s.enrichScaleImpedance(waist.id,500) && !s.enrichScaleImpedance("unknown",500)
                && !s.enrichScaleImpedance(a.id,Double.NaN),"exclude manual, missing and invalid late readings");
        Store.Entry extra = s.saveScale("separate-fixture",when+2,81.1,"linked","test-3");
        ok(s.enrichScaleImpedance(extra.id,455.0) && !s.enrichScaleImpedance(extra.id,456.0)
                && s.find(extra.id).impedanceOhms.equals(455.0),"only first linked impedance accepted");
        s.delete(extra.id);
        ok(s.weightTimesSince(when,when+90000).size()==3,"count weight only");
        ok(s.latestWeight().id.equals("session-B") && s.all().size()==4,"latest weight, same value later");
        Store.Entry correction = s.edit(a.id,when,64.30,86.1,note);
        ok(correction.originalValue.equals("64.35") && correction.weightSource.equals("manual"),"original retained");
        s.delete(waist.id); ok(s.find(waist.id)==null,"delete");
        s.restore(waist); s.restore(waist); ok(s.all().size()==4,"undo retains stable identity");
        TransferCodec codec = new TransferCodec();
        Store.Snapshot snap = s.snapshot();
        String csv = codec.csv(snap.entries);
        ok(csv.contains("'=my note"),"formula guard");
        TransferCodec.Preview csvPreview = codec.parseCsv(csv);
        ok(csvPreview.invalid==0 && csvPreview.entries.size()==4,"CSV round trip");
        ok(csvPreview.entries.stream().filter(e -> e.id.equals("session-B")).findFirst().get().impedanceOhms.equals(500.5),
                "measured impedance survives CSV");
        Store.Entry fromCsv = csvPreview.entries.stream().filter(e -> e.id.equals(a.id)).findFirst().get();
        ok(fromCsv.note.equals(note) && fromCsv.kg.equals(correction.kg) && fromCsv.waistCm.equals(86.1)
                && fromCsv.zoneId.equals(correction.zoneId) && fromCsv.originalValue.equals("64.35")
                && fromCsv.importedMs>0 && fromCsv.modifiedMs==correction.modifiedMs,"CSV all values");
        ok(s.merge(csvPreview.entries,Collections.emptyMap(),false)==0,"re-import skips known IDs");
        String raw = "date_time,weight_lb,waist_cm,note\r\n2026-10-25T01:30:00+01:00,168,,Before clocks change\r\n"
                + "2026-10-25T01:30:00+00:00,168,,After clocks change\r\n"
                + "2026-10-25T01:30:00+00:00,168,,After clocks change\r\nbad input,2,,Bad date\r\n"
                + "2026-10-25T01:30:00Z,NaN,,Bad number\r\n";
        TransferCodec.Preview other = codec.parseCsv(raw);
        ok(other.invalid==2 && other.duplicates==1 && other.entries.size()==2,"CSV preview accounting");
        near(other.entries.get(0).kg,168*0.45359237,"lb conversion");
        ok(codec.parseCsv("time_ms,weight_kg,source\n"+when+",64.35,scale").entries.get(0).weightSource
                .equals("imported"),"external source labelled as imported");
        ok(codec.parseCsv("time_ms,date_time,weight_kg\n"+when+",2026-10-25T01:30:00Z,64.35").invalid==1,
                "inconsistent time columns rejected");
        ok(other.entries.get(1).timeMs-other.entries.get(0).timeMs==3600000L,"DST offsets");
        ok(s.merge(other.entries,Collections.emptyMap(),false)==2 && s.merge(codec.parseCsv(raw).entries,
                Collections.emptyMap(),false)==0,"idless CSV idempotence");
        ok(s.merge(codec.parseCsv("id,time_ms,weight_kg\nsession-A,"+when+",300\n").entries,
                Collections.emptyMap(),false)==0 && s.find(a.id).kg.equals(64.30),"collision preserves local history");
        JSONObject backup = new JSONObject(codec.backup(snap));
        ok(!backup.getJSONObject("settings").has("local.reminder.lastDay"),"exclude transient settings");
        backup.getJSONObject("settings").put("local.permission","1").put("heightCm","NaN");
        backup.getJSONArray("records").put(new JSONObject().put("id","no-time"));
        TransferCodec.Preview restore = codec.parseBackup(backup.toString());
        ok(restore.entries.stream().filter(e -> e.id.equals("session-B")).findFirst().get().impedanceOhms.equals(500.5),
                "measured impedance survives JSON");
        ok(restore.entries.size()==4 && restore.invalid==1 && restore.skippedSettings==2,"backup preview");
        s.delete(manual.id); s.put("unit","kg");
        ok(s.merge(restore.entries,Collections.emptyMap(),false)==1 && "kg".equals(s.get("unit","")),"history only restore");
        ok(s.merge(restore.entries,restore.settings,true)==0 && s.get("unit","").equals("st"),"explicit restore preferences");
        ok(s.find(manual.id).originalValue.equals("12 st 0.2 lb"),"original unit restored");
        try { codec.parseCsv("date_time,weight_kg\n\"unterminated"); throw new AssertionError("malformed quoting"); }
        catch (IllegalArgumentException expected) {}
        try { codec.parseBackup("{\"format\":\"uk.co.violetscale.backup\",\"version\":9}"); throw new AssertionError("bad format"); }
        catch (IllegalArgumentException expected) {}
        Map<String,String> brokenPrefs = new java.util.LinkedHashMap<>();
        brokenPrefs.put("unit","lb"); brokenPrefs.put("","bad");
        try { s.merge(Collections.emptyList(),brokenPrefs,true); throw new AssertionError("bad row"); }
        catch (IllegalArgumentException expected) {}
        ok("st".equals(s.get("unit","")),"merge rollback");
    }
}
