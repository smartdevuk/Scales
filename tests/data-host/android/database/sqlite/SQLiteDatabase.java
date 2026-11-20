package android.database.sqlite;

import android.content.ContentValues;
import android.database.Cursor;
import java.sql.*;
import java.util.*;

/** Minimal Android database adapter backed by a real SQLite file and bound parameters. */
public class SQLiteDatabase {
    public static final int CONFLICT_IGNORE = 4, CONFLICT_REPLACE = 5;
    private final Connection connection;
    private boolean commit;
    SQLiteDatabase(Connection connection) { this.connection=connection; }
    private PreparedStatement prepare(String sql,Object[] args) throws SQLException {
        PreparedStatement s = connection.prepareStatement(sql);
        if (args != null) for (int i=0;i<args.length;i++) s.setObject(i+1,args[i]);
        return s;
    }
    private int execute(String sql,Object[] args) {
        try (PreparedStatement s = prepare(sql,args)) { return s.executeUpdate(); }
        catch (SQLException ex) { throw new RuntimeException(ex); }
    }
    public void execSQL(String sql) { execute(sql,null); }
    public Cursor query(String table,String[] fields,String where,String[] args,String group,String having,String order) {
        return query(table,fields,where,args,group,having,order,null);
    }
    public Cursor query(String table,String[] fields,String where,String[] args,String group,String having,String order,String limit) {
        String q = "SELECT "+String.join(",",fields)+" FROM "+table+(where==null?"":" WHERE "+where)
                +(order==null?"":" ORDER BY "+order)+(limit==null?"":" LIMIT "+limit);
        try { return new Cursor(prepare(q,args)); } catch (SQLException ex) { throw new RuntimeException(ex); }
    }
    public long insertOrThrow(String table,String hack,ContentValues v) { return insertWithOnConflict(table,hack,v,0); }
    public long insertWithOnConflict(String table,String hack,ContentValues v,int strategy) {
        String clause = strategy==CONFLICT_IGNORE ? " OR IGNORE" : strategy==CONFLICT_REPLACE ? " OR REPLACE" : "";
        return execute("INSERT"+clause+" INTO "+table+" ("+String.join(",",v.keySet())+") VALUES ("
                +String.join(",",Collections.nCopies(v.size(),"?"))+")",v.values().toArray());
    }
    public int update(String table,ContentValues v,String where,String[] args) {
        List<Object> parameters = new ArrayList<>(v.values());
        Collections.addAll(parameters,args);
        return execute("UPDATE "+table+" SET "+String.join("=?,",v.keySet())+"=? WHERE "+where,parameters.toArray());
    }
    public int delete(String table,String where,String[] args) { return execute("DELETE FROM "+table+" WHERE "+where,args); }
    public void beginTransaction() {
        try { commit=false; connection.setAutoCommit(false); } catch(SQLException ex) { throw new RuntimeException(ex); }
    }
    public void setTransactionSuccessful() { commit=true; }
    public void endTransaction() {
        try { if (commit) connection.commit(); else connection.rollback(); connection.setAutoCommit(true); }
        catch(SQLException ex) { throw new RuntimeException(ex); }
    }
}
