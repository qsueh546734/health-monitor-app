package android.content;

/** 【桩】见 ContentValues 的说明。只放本项目用到的签名。 */
public class Context {

    public static final String CONNECTIVITY_SERVICE = "connectivity";
    public static final String WIFI_SERVICE = "wifi";
    public static final String AUDIO_SERVICE = "audio";

    public Context() {}

    /** 真实签名是抽象方法，这里给个空实现即可满足类型检查 */
    public Object getSystemService(String name) { return null; }

    public String getPackageName() { return null; }

    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }

    public static final int MODE_PRIVATE = 0;
}
