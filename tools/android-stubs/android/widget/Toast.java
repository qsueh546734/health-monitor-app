package android.widget;

import android.content.Context;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class Toast {

    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;

    public static Toast makeText(Context context, CharSequence text, int duration) { return new Toast(); }

    public static Toast makeText(Context context, int resId, int duration) { return new Toast(); }

    public void show() {}

    public void cancel() {}
}
