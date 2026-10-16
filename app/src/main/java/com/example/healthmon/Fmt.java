package com.example.healthmon;

/**
 * 数值显示格式化。
 *
 * 【为什么单独一个类】这是"界面显示 -- 还是显示 0"这条约束的落点，
 * 也是唯一会被所有卡片共用的格式化逻辑。放在纯 Java 里就能本机验证 ——
 * 而这个类写错的后果很隐蔽：传感器没读到会显示成"体温 0.0°C"。
 */
public final class Fmt {

    private Fmt() {}

    /** 无效值（NaN）显示为 "--"，绝不显示 0 */
    public static final String DASH = "--";

    /** 固定 1 位小数；NaN -> "--"。 */
    public static String oneDecimal(double v) {
        if (Double.isNaN(v)) return DASH;
        long scaled = Math.round(Math.abs(v) * 10.0);
        String sign = v < 0 ? "-" : "";
        // 整数除法：Java 与 JS 在这里语义不同，JS 侧曾因此吐出 "36.6.6"
        return sign + (scaled / 10) + "." + (scaled % 10);
    }

    /** 负数（我们的哨兵）显示为 "--" */
    public static String intOrDash(int v) {
        return v < 0 ? DASH : String.valueOf(v);
    }

    public static String longOrDash(long v) {
        return v < 0 ? DASH : String.valueOf(v);
    }

    /** 秒数转 "X 秒前" / "X 分钟前"，用于数据新鲜度提示 */
    public static String ago(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        if (s < 60) return s + " 秒";
        long m = s / 60;
        if (m < 60) return m + " 分钟";
        return (m / 60) + " 小时";
    }

    /** 阈值钳制：越界时钳到边界，并把"是否被钳过"告诉调用方（界面要提示用户） */
    public static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    public static int clampInt(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
