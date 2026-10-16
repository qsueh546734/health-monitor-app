package android.net;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class NetworkInfo {

    public boolean isConnected() { return true; }

    public boolean isAvailable() { return true; }

    public int getType() { return 0; }

    public String getTypeName() { return ""; }

    public int getState() { return 0; }
}
