package android.database.sqlite;

import android.content.ContentValues;
import android.database.Cursor;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class SQLiteDatabase {

    /** 与真实常量值一致 —— 桩里也要保持可用的语义 */
    public static final int CONFLICT_REPLACE = 5;
    public static final int CONFLICT_IGNORE = 4;
    public static final int CONFLICT_ABORT = 2;

    protected SQLiteDatabase() {}

    public void execSQL(String sql) {}

    public void execSQL(String sql, Object[] bindArgs) {}

    public long insert(String table, String nullColumnHack, ContentValues values) { return 0L; }

    public long insertWithOnConflict(String table, String nullColumnHack, ContentValues values, int conflictAlgorithm) { return 0L; }

    public int update(String table, ContentValues values, String whereClause, String[] whereArgs) { return 0; }

    public int delete(String table, String whereClause, String[] whereArgs) { return 0; }

    public Cursor rawQuery(String sql, String[] selectionArgs) { return null; }

    public void close() {}

    public boolean isOpen() { return true; }

    public void beginTransaction() {}

    public void setTransactionSuccessful() {}

    public void endTransaction() {}
}
