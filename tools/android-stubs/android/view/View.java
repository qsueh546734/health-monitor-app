package android.view;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class View {

    /** 真实 API 里就是 View 的嵌套接口，桩里保持一致，
     *  否则 implements View.OnClickListener 这种写法会编译不过。 */
    public interface OnClickListener {
        void onClick(View v);
    }

    public View() {}

    public View(android.content.Context context) {}

    /** 子类要覆写的生命周期钩子 —— 桩里给出空实现，子类才能正常编译 */
    protected void onDraw(android.graphics.Canvas canvas) {}

    protected void onSizeChanged(int w, int h, int oldw, int oldh) {}

    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {}

    public int getWidth() { return 0; }

    public int getHeight() { return 0; }

    public int getPaddingLeft() { return 0; }

    public int getPaddingTop() { return 0; }

    public int getPaddingRight() { return 0; }

    public int getPaddingBottom() { return 0; }

    public void postInvalidate() {}

    public void setVisibility(int visibility) {}

    public int getVisibility() { return 0; }

    public void setBackgroundColor(int color) {}

    public void setEnabled(boolean enabled) {}

    public boolean isEnabled() { return true; }

    public void setOnClickListener(OnClickListener listener) {}

    public void invalidate() {}

    public void requestLayout() {}

    public void setPadding(int l, int t, int r, int b) {}

    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;
}
