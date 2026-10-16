import com.example.healthmon.Protocol;
import com.example.healthmon.Simulator;
import com.example.healthmon.Frame;

/**
 * 模拟器测试。
 *
 * 模拟器有两个不可替代的用途：
 *   1. 开发期 —— ESP/STM32 还没就绪时，App 的全部界面都能开发完
 *   2. 演示期 —— 现场网络出问题时切过去，仍能演示完整流程
 *
 * 所以它产出的帧必须和真设备【同构】：能被同一个解析器接受、
 * 遵守同一条长度上限、并且缺字段的规则一致。
 * 否则"模拟器能跑"就成了假的安全感。
 */
public class SimulatorTest {

    public static void main(String[] args) {
        framesAreWellFormed();
        sequenceIncrements();
        valuesInRange();
        anomalyBits();
        systemOffFrame();
        T.done();
    }

    /** 核心性质：模拟器吐出的每一帧，真正的解析器都必须能接受。 */
    static void framesAreWellFormed() {
        Simulator s = new Simulator();
        int bad = 0, tooLong = 0, noPrefix = 0;
        for (int i = 0; i < 600; i++) {
            String line = s.next(1000L * i).trim();
            if (Protocol.parseLine(line) == null) bad++;
            if (line.length() > Protocol.MAX_FRAME_BYTES) tooLong++;
            if (!line.startsWith("$D,")) noPrefix++;
        }
        T.eq("wellformed: all 600 frames parse", bad, 0);
        T.eq("wellformed: none exceeds 128 bytes", tooLong, 0);
        T.eq("wellformed: all start with $D,", noPrefix, 0);
    }

    /** 序号必须递增，且遵守 uint16 回绕 —— 否则 App 的丢帧统计会被模拟数据污染。 */
    static void sequenceIncrements() {
        Simulator s = new Simulator();
        int prev = -1;
        int mismatches = 0;
        for (int i = 0; i < 200; i++) {
            Frame f = Protocol.parseLine(s.next(0).trim());
            if (f == null) { mismatches++; continue; }
            if (prev >= 0 && f.seq != ((prev + 1) & 0xFFFF)) mismatches++;
            prev = f.seq;
        }
        T.eq("seq: increments every frame", mismatches, 0);
    }

    /** 数值要落在物理上说得通的范围内 —— 模拟器给出 -300°C 会让演示很难看。 */
    static void valuesInRange() {
        Simulator s = new Simulator();
        int outOfRange = 0;
        for (int i = 0; i < 600; i++) {
            Frame f = Protocol.parseLine(s.next(1000L * i).trim());
            if (f == null) continue;
            if (!Double.isNaN(f.bt) && (f.bt < 30.0 || f.bt > 45.0)) outOfRange++;
            if (f.hr >= 0 && (f.hr < 30 || f.hr > 220)) outOfRange++;
            if (f.water >= 0 && (f.water < 0 || f.water > 100)) outOfRange++;
            if (f.sw != 0 && f.sw != 1) outOfRange++;
        }
        T.eq("range: every value plausible", outOfRange, 0);
    }

    /** 报警位要能被演示脚本逐位打开，否则没法演示报警。 */
    static void anomalyBits() {
        Simulator s = new Simulator();
        s.setAnomaly(Frame.AL_BT, true);
        Frame f = Protocol.parseLine(s.next(0).trim());
        T.ok("anomaly: bt bit set", (f.al & Frame.AL_BT) != 0);
        T.ok("anomaly: other bits clear", (f.al & Frame.AL_HR) == 0);

        // 打开体温报警后，体温必须真的超出常见阈值范围，
        // 否则界面不会变红，演示就"点了没反应"
        T.ok("anomaly: bt actually elevated", f.bt > 37.3);

        s.setAnomaly(Frame.AL_BT, false);
        Frame g = Protocol.parseLine(s.next(0).trim());
        T.ok("anomaly: cleared", (g.al & Frame.AL_BT) == 0);
        T.ok("anomaly: bt back to normal", g.bt <= 37.3);
    }

    /** SW=0 时要产出缺字段帧 —— 和真设备的规则必须一致。 */
    static void systemOffFrame() {
        Simulator s = new Simulator();
        s.setSystemOff(true);
        String line = s.next(0).trim();
        T.ok("off: contains SW=0", line.contains("SW=0"));
        T.ok("off: no BT field", !line.contains("BT="));
        T.ok("off: no HR field", !line.contains("HR="));

        Frame f = Protocol.parseLine(line);
        T.ok("off: parses", f != null);
        T.ok("off: hasBody false", f != null && !f.hasBody());
        T.ok("off: systemOff true", f != null && f.isSystemOff());
    }
}
