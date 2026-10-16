package android.os;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class Looper {
    public static Looper getMainLooper() { return new Looper(); }
    public static Looper myLooper() { return new Looper(); }
}
