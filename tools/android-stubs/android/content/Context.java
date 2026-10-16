package android.content;

/** 【桩】见 ContentValues 的说明。只放本项目用到的签名。 */
public class Context {

    public Context() {}

    /** 真实签名是抽象方法，这里给个空实现即可满足类型检查 */
    public Object getSystemService(String name) { return null; }

    public String getPackageName() { return null; }
}
