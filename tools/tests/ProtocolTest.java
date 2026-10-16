import com.example.healthmon.Frame;
import com.example.healthmon.Protocol;

/**
 * 协议解析测试。
 *
 * 这些用例全部来自设计文档 §3，并且是"用真实设备会吐出的字节"写的 ——
 * 包括固件里那段拼装逻辑实际产生的格式。
 */
public class ProtocolTest {

    public static void main(String[] args) {
        normalFrame();
        invalidValues();
        systemOffFrame();
        garbageRejected();
        seqStatistics();
        T.done();
    }

    /** §3.3 正常帧：所有字段都在 */
    static void normalFrame() {
        Frame f = Protocol.parseLine(
            "$D,SEQ=123,BT=36.5,HR=72,SPO2=98,STEPS=1234,WATER=68,PUMP=0,PMODE=2,SW=1,AL=0,BTHI=37.3,HRHI=100,WLOW=20,*00");
        T.ok("normal: parsed", f != null);
        if (f == null) return;
        T.eq("normal: seq", f.seq, 123);
        T.eq("normal: bt", f.bt, 36.5);
        T.eq("normal: hr", f.hr, 72);
        T.eq("normal: spo2", f.spo2, 98);
        T.eq("normal: steps", f.steps, 1234L);
        T.eq("normal: water", f.water, 68);
        T.eq("normal: pump", f.pump, 0);
        T.eq("normal: pmode", f.pmode, 2);
        T.eq("normal: sw", f.sw, 1);
        T.eq("normal: al", f.al, 0);
        T.eq("normal: bthi", f.btHi, 37.3);
        T.eq("normal: hrhi", f.hrHi, 100);
        T.eq("normal: wlow", f.wLow, 20);
        T.ok("normal: has body", f.hasBody());
    }

    /**
     * §3.2「无效值用 --，不能发 0」—— 这条如果实现错，
     * 界面会把"传感器没读到"显示成"体温 0°C"，比不显示更糟。
     */
    static void invalidValues() {
        Frame f = Protocol.parseLine("$D,SEQ=1,BT=--,HR=--,WATER=50,SW=1,AL=0,*00");
        T.ok("invalid: parsed", f != null);
        if (f == null) return;
        T.ok("invalid: bt is NaN, not 0", Double.isNaN(f.bt));
        T.eq("invalid: hr is -1, not 0", f.hr, -1);
        T.eq("invalid: water still ok", f.water, 50);
        T.eq("invalid: spo2 absent -> -1", f.spo2, -1);
    }

    /**
     * §3.3 SW=0 缺字段帧（拨动开关关掉）。
     * 必须能解析出来，用来区分"用户主动关机"和"链路断了" —— 前者不报警，后者要。
     */
    static void systemOffFrame() {
        Frame f = Protocol.parseLine("$D,SEQ=124,SW=0,AL=0,*00");
        T.ok("systemOff: parsed", f != null);
        if (f == null) return;
        T.eq("systemOff: seq", f.seq, 124);
        T.eq("systemOff: sw", f.sw, 0);
        T.eq("systemOff: al", f.al, 0);
        T.ok("systemOff: bt missing -> NaN", Double.isNaN(f.bt));
        T.eq("systemOff: hr missing -> -1", f.hr, -1);
        T.ok("systemOff: hasBody false (所以界面知道要置灰)", !f.hasBody());
    }

    /** 垃圾输入必须被拒绝，不能解析成一条半真半假的帧 */
    static void garbageRejected() {
        T.ok("garbage: plain text", Protocol.parseLine("hello world") == null);
        T.ok("garbage: no dollar", Protocol.parseLine("D,SEQ=1,*00") == null);
        T.ok("garbage: empty", Protocol.parseLine("") == null);
        T.ok("garbage: null", Protocol.parseLine(null) == null);
        T.ok("garbage: only dollar", Protocol.parseLine("$") == null);
        // 段里没有 '=' 说明是半帧，整帧作废（不能把半截数据当真值）
        T.ok("garbage: field without =", Protocol.parseLine("$D,SEQ=1,BT36.5,SW=1,*00") == null);
        // 帧中间又出现 '$' 说明流已失步（半帧 + 新帧被拼在一起），整条拒绝
        T.ok("garbage: second $ means desync",
             Protocol.parseLine("$D,SEQ=12,BT=36$D,SEQ=13,BT=36.6,SW=1,*00") == null);
    }

    /**
     * 序号与丢帧统计。
     *
     * 这组用例是上一项目的教训：设备复位后序号从 3000 掉回 5，
     * 被实现算成"丢了 65277 帧"，界面上出现荒唐的假数据。
     * 四种情况（首帧/重复/正常/回绕/复位）必须各自分清。
     */
    static void seqStatistics() {
        // 连续帧不算丢
        Protocol p = new Protocol();
        p.noteSeq(100); p.noteSeq(101); p.noteSeq(102);
        T.eq("seq: consecutive -> no loss", p.dropped, 0);

        // uint16 回绕 65535 -> 0 不算丢
        p = new Protocol();
        p.noteSeq(65535); p.noteSeq(0); p.noteSeq(1);
        T.eq("seq: wrap 65535->0 -> no loss", p.dropped, 0);

        // 重复帧不算丢
        p = new Protocol();
        p.noteSeq(10); p.noteSeq(10); p.noteSeq(11);
        T.eq("seq: duplicate -> no loss", p.dropped, 0);
        T.eq("seq: duplicate counted", p.duplicates, 1);

        // 大幅回退 = 设备复位：重新同步，且【绝不虚报丢帧】
        p = new Protocol();
        p.noteSeq(3000); p.noteSeq(5); p.noteSeq(6);
        T.eq("seq: device reset -> no fake loss", p.dropped, 0);
        T.eq("seq: device reset counted", p.resyncs, 1);

        // 真的丢帧要算准
        p = new Protocol();
        p.noteSeq(10); p.noteSeq(13);
        T.eq("seq: real loss counted", p.dropped, 2);

        // 长间隔 + 回绕边界：65533 -> 2 中间丢了 65534,65535,0,1 共 4 帧
        p = new Protocol();
        p.noteSeq(65533); p.noteSeq(2);
        T.eq("seq: loss across wrap", p.dropped, 4);

        // reset() 必须把统计清干净
        p = new Protocol();
        p.noteSeq(1); p.noteSeq(9);
        p.reset();
        T.eq("seq: reset clears dropped", p.dropped, 0);
        T.eq("seq: reset clears duplicates", p.duplicates, 0);
        T.eq("seq: reset clears resyncs", p.resyncs, 0);
    }
}
