package uk.co.violetscale.probe;

import android.content.Context;
import java.io.File;
import java.sql.*;

public final class DataTestHost {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        if (args[0].startsWith("migration")) {
            boolean fromV2 = "migration2".equals(args[0]);
            try (Connection c=DriverManager.getConnection("jdbc:sqlite:"+
                    new File(System.getProperty("violet.testDB"),"violet-history.db")); Statement s=c.createStatement()) {
                s.execute("CREATE TABLE entries (id TEXT PRIMARY KEY,time_ms INTEGER,received_ms INTEGER,zone_id TEXT,"
                        +"offset_min INTEGER,weight_kg REAL,waist_cm REAL,note TEXT,source TEXT,weight_source TEXT,"
                        +"waist_source TEXT,device TEXT,decoder TEXT,original_value TEXT,original_unit TEXT,"
                        +"original_resolution TEXT,imported_ms INTEGER)");
                s.execute("CREATE TABLE settings (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
                s.execute("INSERT INTO entries VALUES ('v1',2000,2100,'UTC',0,70,NULL,'old','manual','manual','',"
                        +"'','','70','kg','',0)");
                s.execute("INSERT INTO settings VALUES ('unit','lb')");
                if (fromV2) {
                    s.execute("ALTER TABLE entries ADD COLUMN modified_ms INTEGER NOT NULL DEFAULT 0");
                    s.execute("ALTER TABLE entries ADD COLUMN height_cm_at_entry REAL");
                    s.execute("UPDATE entries SET modified_ms=3150, height_cm_at_entry=188");
                }
                s.execute("PRAGMA user_version="+(fromV2 ? 2 : 1));
            }
            Store store = new Store(new Context());
            Store.Entry old = store.find("v1");
            if (old == null || old.kg!=70 || old.modifiedMs!=(fromV2 ? 3150 : 2100) || old.impedanceOhms!=null ||
                    !java.util.Objects.equals(old.heightCmAtEntry,fromV2 ? Double.valueOf(188.0) : null) ||
                    !store.get("unit","").equals("lb")) throw new AssertionError("migration");
        } else if ("restart".equals(args[0])) {
            Store store = new Store(new Context());
            if (store.all().size()!=6 || !store.get("unit","").equals("st")
                    || store.find("session-A").kg!=64.30) throw new AssertionError("persist across process restart");
        } else DataFixtures.run(new Store(new Context()));
        System.out.println("PASS data " + args[0]);
    }
}
