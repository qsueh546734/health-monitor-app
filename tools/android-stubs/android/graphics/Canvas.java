package android.graphics;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class Canvas {

    public Canvas() {}

    public Canvas(Object bitmap) {}

    public void drawColor(int color) {}

    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {}

    public void drawPath(Path path, Paint paint) {}

    public void drawCircle(float cx, float cy, float radius, Paint paint) {}

    public void drawRect(float left, float top, float right, float bottom, Paint paint) {}

    public void drawText(String text, float x, float y, Paint paint) {}

    public int getWidth() { return 0; }

    public int getHeight() { return 0; }

    public void drawTextAlign(String text, float x, float y, Paint paint) {}
}
