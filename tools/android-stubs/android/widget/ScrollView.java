package android.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class ScrollView extends ViewGroup {

    public ScrollView(Context context) { super(); }

    public void addView(View child) {}

    public void setFillViewport(boolean fill) {}

    public void fullScroll(int direction) {}

    public static final int FOCUS_DOWN = 130;
}
