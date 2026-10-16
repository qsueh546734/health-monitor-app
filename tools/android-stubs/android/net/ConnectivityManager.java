package android.net;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class ConnectivityManager {

    public static final int TYPE_MOBILE = 0;
    public static final int TYPE_WIFI = 1;
    public static final int TYPE_ETHERNET = 9;

    public NetworkInfo getActiveNetworkInfo() { return null; }

    public NetworkInfo[] getAllNetworkInfo() { return new NetworkInfo[0]; }

    public boolean isActiveNetworkMetered() { return false; }
}
