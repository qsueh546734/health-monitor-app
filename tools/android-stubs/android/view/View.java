package android.view;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class View {

    public View() {}

    public void setVisibility(int visibility) {}

    public int getVisibility() { return 0; }

    public void setBackgroundColor(int color) {}

    public void setEnabled(boolean enabled) {}

    public boolean isEnabled() { return true; }

    public void setOnClickListener(Object listener) {}

    public void invalidate() {}

    public void requestLayout() {}

    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;
}
