package android.os;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class Bundle {

    public Bundle() {}

    public void putString(String key, String value) {}

    public String getString(String key) { return null; }

    public void putInt(String key, int value) {}

    public int getInt(String key) { return 0; }

    public int getInt(String key, int defaultValue) { return defaultValue; }

    public void putLong(String key, long value) {}

    public long getLong(String key) { return 0L; }

    public void putBoolean(String key, boolean value) {}

    public boolean getBoolean(String key) { return false; }

    public boolean containsKey(String key) { return false; }
}
