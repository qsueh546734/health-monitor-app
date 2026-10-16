package android.graphics;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class Paint {

    public static final int ANTI_ALIAS_FLAG = 1;
    public static final int FILL = 0;
    public static final int STROKE = 1;
    public static final int FILL_AND_STROKE = 2;

    public static class Style {
        public static final Style FILL = new Style();
        public static final Style STROKE = new Style();
        public static final Style FILL_AND_STROKE = new Style();
    }

    public static class Align {
        public static final Align LEFT = new Align();
        public static final Align CENTER = new Align();
        public static final Align RIGHT = new Align();
    }

    public Paint() {}

    public Paint(int flags) {}

    public void setColor(int color) {}

    public void setStrokeWidth(float width) {}

    public void setStrokeCap(Object cap) {}

    public void setAntiAlias(boolean aa) {}

    public void setStyle(Style style) {}

    public void setTextSize(float textSize) {}

    public void setTextAlign(Align align) {}

    public void setFakeBoldText(boolean bold) {}

    public float measureText(String text) { return 0f; }
}
