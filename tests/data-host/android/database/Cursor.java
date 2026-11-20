package android.database;

import java.sql.*;
import java.sql.SQLException;

public class Cursor implements AutoCloseable {
    private final ResultSet result;
    private final PreparedStatement statement;
    public Cursor(PreparedStatement statement) throws SQLException {
        this.statement = statement;
        this.result = statement.executeQuery();
    }
    public boolean moveToNext() { try { return result.next(); } catch (SQLException e) { throw new RuntimeException(e); } }
    public boolean moveToFirst() { return moveToNext(); }
    public String getString(int i) { try { return result.getString(i+1); } catch (SQLException e) { throw new RuntimeException(e); } }
    public long getLong(int i) { try { return result.getLong(i+1); } catch (SQLException e) { throw new RuntimeException(e); } }
    public int getInt(int i) { return (int)getLong(i); }
    public double getDouble(int i) { try { return result.getDouble(i+1); } catch (SQLException e) { throw new RuntimeException(e); } }
    public boolean isNull(int i) { try { return result.getObject(i+1)==null; } catch (SQLException e) { throw new RuntimeException(e); } }
    public void close() { try { statement.close(); } catch (SQLException e) { throw new RuntimeException(e); } }
}
