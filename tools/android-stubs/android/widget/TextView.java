package android.widget;

import android.content.Context;
import android.view.View;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class TextView extends View {

    public TextView(Context context) {}

    public void setText(CharSequence text) {}

    public void setText(int resId) {}

    public CharSequence getText() { return ""; }

    public void setTextColor(int color) {}

    public void setTextSize(float size) {}

    public void setTypeface(Object tf) {}

    public void setGravity(int gravity) {}

    public void setPadding(int l, int t, int r, int b) {}

    public void setSingleLine(boolean singleLine) {}

    public int getVisibility() { return 0; }
}
