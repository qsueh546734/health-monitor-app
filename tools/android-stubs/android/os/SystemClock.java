package android.os;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class SystemClock {
    public static long uptimeMillis() { return 0L; }
    public static long elapsedRealtime() { return 0L; }
    public static void sleep(long ms) {}
}
