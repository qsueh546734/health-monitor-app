package com.example.healthmon;

import android.app.Activity;
import android.os.Bundle;

/**
 * 单 Activity，多页面。
 *
 * Task 1 阶段它只显示一句话，目的是把「写代码 → 云构建 → 下载 → 装到手机 → 打开」
 * 这整条链路先走通。管线不通的话，后面每个功能细节都白讨论。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
