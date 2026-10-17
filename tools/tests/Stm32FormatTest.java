import com.example.healthmon.Frame;
import com.example.healthmon.Protocol;

/**
 * 跨语言格式测试：STM32（C）→ App（Java）。
 *
 * 下面的字符串【不是手写的】，是把 stm32/proto.c 真的编译运行之后，
 * 从它实际发出的字节里抓下来的（见 stm32/proto_host_test.c）。
 *
 * 为什么必须做这一层：C 侧和 Java 侧各自照同一份文档实现，
 * 只要有一边在细节上错一位 —— 逗号位置、小数位数、校验位写法、
 * 字段名大小写 —— 整条链路就不通。而两边的单元测试各自全绿，
 * 谁也发现不了。只有把真实字节喂给真解析器才看得见。
 *
 * 同目录的 FakeDeviceFormatTest 覆盖的是 Node（假设备）→ Java；
 * 这个文件覆盖的是 C（真固件参考实现）→ Java。两条都不能省。
 */
public class Stm32FormatTest {

    // ---- 由 stm32/proto_host_test.c 实际运行产生 ----
    static final String NORMAL =
        "$D,SEQ=0,BT=36.5,HR=72,SPO2=--,STEPS=1234,WATER=68,PUMP=0,PMODE=2,SW=1,AL=0,BTHI=37.3,HRHI=100,WLOW=20,*00";
    static final String INVALID_VALUES =
        "$D,SEQ=1,BT=--,HR=--,SPO2=--,STEPS=1234,WATER=68,PUMP=0,PMODE=2,SW=1,AL=0,BTHI=37.3,HRHI=100,WLOW=20,*00";
    static final String SYSTEM_OFF =
        "$D,SEQ=2,SW=0,AL=0,*00";
    static final String AFTER_TH =
        "$D,SEQ=0,BT=36.5,HR=72,SPO2=--,STEPS=1234,WATER=68,PUMP=0,PMODE=2,SW=1,AL=0,BTHI=37.5,HRHI=105,WLOW=25,*00";
    static final String AFTER_PUMP_MANUAL =
        "$D,SEQ=1,BT=36.5,HR=72,SPO2=--,STEPS=1234,WATER=68,PUMP=1,PMODE=1,SW=1,AL=0,BTHI=37.5,HRHI=105,WLOW=25,*00";
    static final String AFTER_TH_CLAMPED =
        "$D,SEQ=0,BT=36.5,HR=72,SPO2=--,STEPS=1234,WATER=68,PUMP=0,PMODE=2,SW=1,AL=0,BTHI=42.0,HRHI=180,WLOW=100,*00";
    static final String WORST_CASE =
        "$D,SEQ=0,BT=45.0,HR=220,SPO2=100,STEPS=999999,WATER=100,PUMP=1,PMODE=2,SW=1,AL=15,BTHI=42.0,HRHI=180,WLOW=100,*00";

    public static void main(String[] args) {
        normalFrame();
        invalidValues();
        systemOffFrame();
        thresholdEcho();
        pumpModeEcho();
        clamping();
        worstCaseFits();
        T.done();
    }

    /** 正常帧：每个字段都必须真的解析出来（不只是"整帧能解析"） */
    static void normalFrame() {
        Frame f = Protocol.parseLine(NORMAL);
        T.ok("stm32: normal parses", f != null);
        if (f == null) return;
        T.eq("stm32: SEQ", f.seq, 0);
        T.ok("stm32: BT parsed", !Double.isNaN(f.bt));
        T.eq("stm32: BT value", f.bt, 36.5);
        T.eq("stm32: HR", f.hr, 72);
        T.eq("stm32: STEPS", f.steps, 1234L);
        T.eq("stm32: WATER", f.water, 68);
        T.eq("stm32: PUMP", f.pump, 0);
        T.eq("stm32: PMODE", f.pmode, 2);
        T.eq("stm32: SW", f.sw, 1);
        T.eq("stm32: AL", f.al, 0);
        T.ok("stm32: BTHI parsed", !Double.isNaN(f.btHi));
        T.eq("stm32: BTHI value", f.btHi, 37.3);
        T.eq("stm32: HRHI", f.hrHi, 100);
        T.eq("stm32: WLOW", f.wLow, 20);
    }

    /** C 侧对无效值发的是 "--"，Java 侧必须变成哨兵而不是 0 */
    static void invalidValues() {
        Frame f = Protocol.parseLine(INVALID_VALUES);
        T.ok("stm32: invalid parses", f != null);
        if (f == null) return;
        T.ok("stm32: BT -- -> NaN (not 0)", Double.isNaN(f.bt));
        T.eq("stm32: HR -- -> -1 (not 0)", f.hr, -1);
        T.eq("stm32: SPO2 -- -> -1", f.spo2, -1);
        T.eq("stm32: STEPS still valid", f.steps, 1234L);
    }

    /** SW=0 帧只有三个字段，必须能和"链路断了"区分开 */
    static void systemOffFrame() {
        Frame f = Protocol.parseLine(SYSTEM_OFF);
        T.ok("stm32: system-off parses", f != null);
        if (f == null) return;
        T.eq("stm32: SW=0", f.sw, 0);
        T.ok("stm32: isSystemOff", f.isSystemOff());
        T.ok("stm32: hasBody false", !f.hasBody());
        T.ok("stm32: BT missing -> NaN", Double.isNaN(f.bt));
    }

    /** 收到 TH 后，下一帧的阈值必须变 —— 这就是隐式 ACK 的判据来源 */
    static void thresholdEcho() {
        Frame before = Protocol.parseLine(NORMAL);
        Frame after = Protocol.parseLine(AFTER_TH);
        T.ok("stm32: TH changed BTHI", Math.abs(after.btHi - before.btHi) > 0.05);
        T.eq("stm32: TH set HRHI=105", after.hrHi, 105);
        T.eq("stm32: TH set WLOW=25", after.wLow, 25);

        // App 侧的隐式 ACK 判定：命令之后的新帧才允许确认
        com.example.healthmon.AppState s = new com.example.healthmon.AppState();
        s.onFrame(before);
        s.markCommandSent(37.5);
        T.ok("stm32: stale frame must not confirm", !s.isConfirmed(before));
        s.onFrame(after);
        T.ok("stm32: new frame confirms the TH command", s.isConfirmed(after));
    }

    /** 水泵模式看 PMODE 而不是 PUMP */
    static void pumpModeEcho() {
        Frame f = Protocol.parseLine(AFTER_PUMP_MANUAL);
        T.eq("stm32: PMODE=1 after manual-on", f.pmode, 1);
        T.eq("stm32: PUMP=1 after manual-on", f.pump, 1);
    }

    /** 越界阈值必须被 C 侧钳制到合法范围 */
    static void clamping() {
        Frame f = Protocol.parseLine(AFTER_TH_CLAMPED);
        T.eq("stm32: BTHI clamped to 42.0", f.btHi, 42.0);
        T.eq("stm32: HRHI clamped to 180", f.hrHi, 180);
        T.eq("stm32: WLOW clamped to 100", f.wLow, 100);
        T.ok("stm32: BTHI within protocol range", f.btHi >= 35.0 && f.btHi <= 42.0);
    }

    /** 最坏情况的帧必须落在协议上限内，且仍然能解析 */
    static void worstCaseFits() {
        T.ok("stm32: worst case <= " + Protocol.MAX_FRAME_BYTES + " bytes",
             WORST_CASE.length() <= Protocol.MAX_FRAME_BYTES);
        Frame f = Protocol.parseLine(WORST_CASE);
        T.ok("stm32: worst case still parses", f != null);
        if (f == null) return;
        T.eq("stm32: worst AL=15 all bits", f.al, 15);
        T.eq("stm32: worst steps max", f.steps, 999999L);
    }
}
