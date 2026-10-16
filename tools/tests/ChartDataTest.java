import com.example.healthmon.ChartData;

import java.util.ArrayList;
import java.util.List;

/**
 * 图表取数测试。
 *
 * 自绘曲线不能一次画 5 万个点 —— 那会把主线程卡死。
 * 降采样必须满足两条：点数受控，且【首尾一定保留】，
 * 否则用户看到的时间范围会比实际短，是会误导人的。
 */
public class ChartDataTest {

    public static void main(String[] args) {
        respectsCap();
        keepsEndpoints();
        passthroughWhenSmall();
        emptySafe();
        rangeComputation();
        T.done();
    }

    static List<long[]> series(int n) {
        List<long[]> pts = new ArrayList<long[]>();
        for (int i = 0; i < n; i++) pts.add(new long[]{i * 1000L, 360 + (i % 10)});
        return pts;
    }

    static void respectsCap() {
        List<long[]> out = ChartData.downsample(series(5000), 300);
        T.ok("cap: size <= 300", out.size() <= 300);
        T.ok("cap: produced something", out.size() > 0);
    }

    /** 首尾必须保留，否则曲线看起来像被截断。 */
    static void keepsEndpoints() {
        List<long[]> in = series(5000);
        List<long[]> out = ChartData.downsample(in, 300);
        T.eq("endpoints: first ts kept", out.get(0)[0], in.get(0)[0]);
        T.eq("endpoints: last ts kept", out.get(out.size() - 1)[0], in.get(in.size() - 1)[0]);
    }

    static void passthroughWhenSmall() {
        List<long[]> in = series(10);
        T.eq("small: unchanged", ChartData.downsample(in, 300).size(), 10);
    }

    /** 空数据不能抛异常 —— 历史页刚打开时会先查库再赋值，中间就是空的。 */
    static void emptySafe() {
        T.eq("empty: list", ChartData.downsample(new ArrayList<long[]>(), 300).size(), 0);
        T.eq("empty: null", ChartData.downsample(null, 300).size(), 0);
        long[] r = ChartData.range(new ArrayList<long[]>());
        T.eq("empty: range minVal", r[0], 0L);
        T.eq("empty: range maxVal", r[1], 0L);
    }

    static void rangeComputation() {
        List<long[]> r = new ArrayList<long[]>();
        r.add(new long[]{100L, 50L});
        r.add(new long[]{300L, 20L});
        r.add(new long[]{200L, 80L});
        long[] rng = ChartData.range(r);
        T.eq("range: minVal", rng[0], 20L);
        T.eq("range: maxVal", rng[1], 80L);
        T.eq("range: minTs", rng[2], 100L);
        T.eq("range: maxTs", rng[3], 300L);
    }
}
