import com.example.healthmon.Frame;
import com.example.healthmon.Protocol;

/**
 * 跨语言格式测试。
 *
 * 下面的字符串【不是手写的】，是用 tools/capture-frames.js 从
 * tools/fake-device.js 真实抓下来的字节。它们代表"另一端实际会发什么"。
 *
 * 为什么需要这一层：Java 的 Simulator 和 JS 的假设备各自照文档实现，
 * 只要有一边在细节上错位（小数位数、字段名、无效值写法），
 * 单侧测试全都发现不了。必须把真实字节喂给真解析器。
 *
 * 这个测试确实抓到过一个真 bug：假设备的 fmt1 用了 JS 的浮点除法，
 * 吐出 "BT=36.6.6"。而 Double.valueOf("36.6.6") 抛异常、解析器保留哨兵、
 * 界面显示 "--" —— 不报错，只是悄悄没有数据。
 * 所以下面每组都断言"数值字段必须真的解析出来了"，而不只是"整帧能解析"。
 */
public class FakeDeviceFormatTest {

    // ---- 由 tools/capture-frames.js 抓取（假设备启动后的前几帧）----
    static final String[] CAPTURED = {
        "$I,IP=127.0.0.1,PORT=8299,MODE=AP,*00",
        "$D,SEQ=0,BT=36.5,HR=82,SPO2=--,STEPS=1,WATER=80,PUMP=0,PMODE=2,SW=1,AL=0,BTHI=37.5,HRHI=105,WLOW=25,*00",
        "$D,SEQ=1,BT=36.5,HR=83,SPO2=--,STEPS=3,WATER=79,PUMP=1,PMODE=1,SW=1,AL=0,BTHI=37.5,HRHI=105,WLOW=25,*00",
        "$D,SEQ=2,BT=36.5,HR=85,SPO2=--,STEPS=5,WATER=79,PUMP=1,PMODE=1,SW=1,AL=0,BTHI=37.5,HRHI=105,WLOW=25,*00",
        "$D,SEQ=3,BT=36.6,HR=87,SPO2=--,STEPS=7,WATER=79,PUMP=1,PMODE=1,SW=1,AL=0,BTHI=37.5,HRHI=105,WLOW=25,*00",
    };

    public static void main(String[] args) {
        capturedDataFramesParseCompletely();
        infoFrameIsNotADataFrame();
        everyCapturedFrameFitsTheLimit();
        thresholdEchoLanded();
        T.done();
    }

    /**
     * 每个 $D 帧里，假设备声明要发的数值字段都必须【真的解析出来】。
     *
     * 只说"整帧能解析"是不够的 —— 畸形数字会让单字段回落到哨兵，
     * 整帧照样返回非 null。必须逐个字段检查。
     */
    static void capturedDataFramesParseCompletely() {
        int checked = 0;
        for (String line : CAPTURED) {
            if (!line.startsWith("$D")) continue;
            checked++;
            Frame f = Protocol.parseLine(line);
            T.ok("captured: frame parses [" + line.substring(0, 20) + "...]", f != null);
            if (f == null) continue;
            T.ok("captured: SEQ present", f.seq >= 0);
            T.ok("captured: BT parsed (not NaN) -- catches 36.6.6 style bugs", !Double.isNaN(f.bt));
            T.ok("captured: HR parsed", f.hr >= 0);
            T.ok("captured: STEPS parsed", f.steps >= 0);
            T.ok("captured: WATER parsed", f.water >= 0);
            T.ok("captured: PUMP parsed", f.pump >= 0);
            T.ok("captured: PMODE parsed", f.pmode >= 0);
            T.ok("captured: SW parsed", f.sw >= 0);
            T.ok("captured: BTHI parsed", !Double.isNaN(f.btHi));
            T.ok("captured: HRHI parsed", f.hrHi >= 0);
            T.ok("captured: WLOW parsed", f.wLow >= 0);
            // SPO2 此时应当是无效值（血氧 P2 前不做），必须回落成哨兵而不是 0
            T.eq("captured: SPO2 -- is sentinel -1, not 0", f.spo2, -1);
        }
        T.ok("captured: actually had data frames to check", checked >= 3);
    }

    /** $I 是 ESP 状态帧，字段与 $D 完全不同，不能被当成数据帧解析。 */
    static void infoFrameIsNotADataFrame() {
        Frame f = Protocol.parseLine(CAPTURED[0]);
        T.ok("info: $I frame is rejected by parseLine (它由 ESP 相关代码单独处理)", f == null);
    }

    /** 每一帧都必须落在协议规定的长度上限内。 */
    static void everyCapturedFrameFitsTheLimit() {
        for (String line : CAPTURED) {
            T.ok("limit: <= " + Protocol.MAX_FRAME_BYTES + " bytes", line.length() <= Protocol.MAX_FRAME_BYTES);
        }
    }

    /** TH 命令的阈值必须在后续帧里回显 —— 这是隐式 ACK 的判据来源。 */
    static void thresholdEchoLanded() {
        Frame f = Protocol.parseLine(CAPTURED[1]);
        T.ok("echo: BTHI=37.5 from the TH command", f != null && Math.abs(f.btHi - 37.5) < 0.05);
        T.eq("echo: HRHI=105 from the TH command", f == null ? -1 : f.hrHi, 105);
        T.eq("echo: WLOW=25 from the TH command", f == null ? -1 : f.wLow, 25);

        // PUMP MODE=1 之后 PMODE 应变为 1（不是看 PUMP —— 自动模式下水泵会随水位自己动）
        Frame g = Protocol.parseLine(CAPTURED[2]);
        T.eq("echo: PMODE=1 from the PUMP command", g == null ? -1 : g.pmode, 1);
    }
}
