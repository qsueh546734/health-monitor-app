package com.example.healthmon;

/**
 * 界面共用的小工具与配色。
 *
 * 配色集中在这里，是为了让"正常/警告/异常/停用"四种状态在全 App 里一致 ——
 * 散落各处的话，同一个"报警红"迟早会出现两三个不同的值。
 */
public final class Ui {

    private Ui() {}

    /** 正常 */
    public static final int OK = 0xFF2E7D32;      // 深绿
    /** 需要注意（比如水位低、水泵手动模式） */
    public static final int WARN = 0xFFEF6C00;    // 橙
    /** 报警 */
    public static final int BAD = 0xFFC62828;     // 红
    /** 停用 / 无数据 */
    public static final int DIM = 0xFF9E9E9E;     // 灰
    /** 正文 */
    public static final int TEXT = 0xFF212121;
}
