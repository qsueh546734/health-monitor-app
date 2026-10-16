package android.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class LinearLayout extends ViewGroup {

    public LinearLayout(Context context) { super(); }

    public void setOrientation(int orientation) {}

    public void setGravity(int gravity) {}

    public void setPadding(int l, int t, int r, int b) {}

    public void setLayoutParams(Object params) {}

    public void addView(View child) {}

    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;
}
