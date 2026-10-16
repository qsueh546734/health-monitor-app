package android.app;

import android.content.Context;
import android.os.Bundle;

/** 【桩】见 android/content/ContentValues.java 的说明。只放本项目用到的签名。 */
public class Activity extends Context {

    public Activity() {}

    protected void onCreate(Bundle savedInstanceState) {}

    protected void onStart() {}

    protected void onResume() {}

    protected void onPause() {}

    protected void onStop() {}

    protected void onDestroy() {}

    protected void onSaveInstanceState(Bundle outState) {}

    public void setContentView(int layoutResID) {}

    public void setTitle(CharSequence title) {}

    public void setTitle(int titleId) {}

    public void finish() {}

    public Context getApplicationContext() { return null; }

    /** 真实签名是 <T extends View> T findViewById(int)。桩里用 Object 会让调用点
     *  需要强转，反而掩盖类型错误 —— 所以至少要有个 View 类型。见 android/view/View.java。 */
    public android.view.View findViewById(int id) { return null; }
}
