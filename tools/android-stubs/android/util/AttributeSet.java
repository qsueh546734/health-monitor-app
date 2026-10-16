package android.util;

/** 【桩】见 android/content/ContentValues.java 的说明。
 *
 *  自定义 View 从 XML 里被充气时，Android 要求有 (Context, AttributeSet) 构造函数 ——
 *  桩里也得有 AttributeSet，否则那个构造函数写不出来、本机检查也发现不了缺它。
 */
public class AttributeSet {

    public int getAttributeCount() { return 0; }

    public String getAttributeName(int index) { return null; }

    public String getAttributeValue(int index) { return null; }
}
