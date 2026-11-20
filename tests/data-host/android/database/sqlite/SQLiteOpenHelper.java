package android.database.sqlite;

import android.content.Context;
import java.io.File;
import java.sql.*;

public abstract class SQLiteOpenHelper {
    private final SQLiteDatabase db;
    public SQLiteOpenHelper(Context context,String name,Object factory,int version) {
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:"+new File(System.getProperty("violet.testDB"),name));
            db = new SQLiteDatabase(connection);
            try (Statement s=connection.createStatement();
                 ResultSet rows=s.executeQuery("PRAGMA user_version")) {
                int previous = rows.getInt(1);
                rows.close();
                if (previous != version) {
                    db.beginTransaction();
                    try {
                        if (previous==0) onCreate(db); else onUpgrade(db,previous,version);
                        db.execSQL("PRAGMA user_version="+version);
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                }
            }
        } catch(SQLException e) { throw new RuntimeException(e); }
    }
    public abstract void onCreate(SQLiteDatabase db);
    public abstract void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion);
    public SQLiteDatabase getReadableDatabase() { return db; }
    public SQLiteDatabase getWritableDatabase() { return db; }
}
