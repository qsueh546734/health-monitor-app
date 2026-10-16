package android.database;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public interface Cursor {

    boolean moveToFirst();

    boolean moveToNext();

    int getCount();

    int getPosition();

    void close();

    boolean isNull(int columnIndex);

    long getLong(int columnIndex);

    int getInt(int columnIndex);

    double getDouble(int columnIndex);

    String getString(int columnIndex);

    int getColumnIndex(String columnName);

    int getColumnIndexOrThrow(String columnName);
}
