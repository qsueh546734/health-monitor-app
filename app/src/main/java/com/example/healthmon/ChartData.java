package com.example.healthmon;

import java.util.ArrayList;
import java.util.List;

/**
 * 曲线的取数与变换。
 *
 * 纯 Java、可秒级测试 —— 因为自绘曲线的两条硬性质必须钉住：
 *   1. 点数受控（5 万个点直接画会把主线程卡死）
 *   2. 首尾一定保留（否则用户看到的时间范围比实际短，是会误导人的）
 *
 * 点的表示：每个点是 long[]{时间戳, 数值×10}。
 * 用整数存是为了避免浮点比较带来的麻烦，画图时除以 10 还原。
 */
public final class ChartData {

    private ChartData() {}

    /**
     * 等间隔抽稀，但【首尾一定保留】。
     * 点数不超过上限时原样返回（不做无谓的变换，省一次分配）。
     */
    public static List<long[]> downsample(List<long[]> pts, int maxPoints) {
        List<long[]> out = new ArrayList<long[]>();
        if (pts == null || pts.isEmpty()) return out;

        int n = pts.size();
        if (maxPoints <= 0 || n <= maxPoints) {
            out.addAll(pts);
            return out;
        }
        if (maxPoints == 1) {
            out.add(pts.get(n - 1));
            return out;
        }

        double step = (double) (n - 1) / (maxPoints - 1);
        for (int i = 0; i < maxPoints; i++) {
            int idx = (int) Math.round(i * step);
            if (idx >= n) idx = n - 1;
            out.add(pts.get(idx));
        }
        return out;
    }

    /** 返回 {minVal, maxVal, minTs, maxTs}；空数据返回全 0 */
    public static long[] range(List<long[]> pts) {
        if (pts == null || pts.isEmpty()) return new long[]{0L, 0L, 0L, 0L};
        long minV = Long.MAX_VALUE, maxV = Long.MIN_VALUE;
        long minT = Long.MAX_VALUE, maxT = Long.MIN_VALUE;
        for (long[] p : pts) {
            if (p[1] < minV) minV = p[1];
            if (p[1] > maxV) maxV = p[1];
            if (p[0] < minT) minT = p[0];
            if (p[0] > maxT) maxT = p[0];
        }
        return new long[]{minV, maxV, minT, maxT};
    }
}
