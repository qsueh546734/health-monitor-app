package android.content;

/**
 * 【桩】只为让本机 javac 能对项目源码做真正的类型检查。
 *
 * 为什么需要它：没有 SDK 时，javac 解析不了 android.* 的类，于是【不再检查
 * 用到这些类的方法体】—— 实测结果是往 Db.java 里注入"返回值类型不匹配"
 * "方法名拼错""引用不存在的字段"这类错误，检查器一个都抓不到（1/4）。
 * 那样所有 Android 代码就只能靠 8 分钟一次的云构建验证。
 *
 * 【纪律】桩是近似，不是真相：
 *   - 绝不放进 app/src/main/java（会打进 APK）
 *   - 桩与 CI 冲突时以 CI 为准，并立刻修桩
 *   - 只放本项目真正用到的签名，不追求覆盖 API
 */
public class ContentValues {

    public ContentValues() {}

    public ContentValues(int size) {}

    public void put(String key, String value) {}

    public void put(String key, int value) {}

    public void put(String key, long value) {}

    public void put(String key, double value) {}

    public void put(String key, boolean value) {}

    public void putNull(String key) {}

    public int size() { return 0; }

    public void clear() {}

    public boolean containsKey(String key) { return false; }

    public String getAsString(String key) { return null; }

    public Integer getAsInteger(String key) { return null; }
}
