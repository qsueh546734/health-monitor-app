package android.database.sqlite;

import android.content.Context;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public abstract class SQLiteOpenHelper {

    public interface CursorFactory {}

    public SQLiteOpenHelper(Context context, String name, CursorFactory factory, int version) {}

    public abstract void onCreate(SQLiteDatabase db);

    public abstract void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion);

    public SQLiteDatabase getWritableDatabase() { return null; }

    public SQLiteDatabase getReadableDatabase() { return null; }

    public synchronized void close() {}

    public void onOpen(SQLiteDatabase db) {}

    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
}
