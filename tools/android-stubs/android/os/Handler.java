package android.os;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class Handler {

    public Handler() {}

    public Handler(Looper looper) {}

    public boolean post(Runnable r) { return true; }

    public boolean postDelayed(Runnable r, long delayMillis) { return true; }

    public void removeCallbacks(Runnable r) {}

    public void removeCallbacksAndMessages(Object token) {}
}
