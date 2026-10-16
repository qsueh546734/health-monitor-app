import com.example.healthmon.AppState;
import com.example.healthmon.Frame;
import com.example.healthmon.Protocol;

/**
 * 状态机测试：隐式 ACK、数据新鲜度、报警节流。
 *
 * 这三件事都是"界面上看起来对、实际已经错了"的高发区，
 * 所以每条判据都要用测试钉死，而不是靠加界面时顺手写对。
 */
public class AppStateTest {

    public static void main(String[] args) {
        implicitAck();
        commandVariants();
        staleness();
        systemOff();
        alertThrottle();
        T.done();
    }

    /**
     * 隐式 ACK 的核心：判据必须是【命令之后新到的帧】。
     *
     * 上一项目的真 bug：急停按钮显示"已确认"，其实命令根本没发出去 ——
     * 因为判据只看"当前值等于期望值"，而残留状态刚好满足。
     */
    static void implicitAck() {
        AppState s = new AppState();

        // 先来一帧，阈值恰好是 37.3（命令想设的也是 37.3）
        Frame old = Protocol.parseLine("$D,SEQ=1,BTHI=37.3,SW=1,AL=0,*00");
        s.onFrame(old);
        T.ok("ack: first frame ingested", s.last != null);

        s.markCommandSent(37.5);
        T.ok("ack: stale frame must NOT confirm", !s.isConfirmed(old));

        // 命令之后来了新帧，且阈值已变成新值
        Frame now = Protocol.parseLine("$D,SEQ=2,BTHI=37.5,SW=1,AL=0,*00");
        s.onFrame(now);
        T.ok("ack: new frame confirms", s.isConfirmed(now));

        // 没发过命令时永远不确认
        AppState t = new AppState();
        Frame f = Protocol.parseLine("$D,SEQ=1,BTHI=37.5,SW=1,AL=0,*00");
        t.onFrame(f);
        T.ok("ack: no command -> not confirmed", !t.isConfirmed(f));

        // clearCommand 之后不再确认
        t.clearCommand();
        T.ok("ack: after clear -> not confirmed", !t.isConfirmed(f));
    }

    /** 每种命令看的是各自的回显字段，不能串。 */
    static void commandVariants() {
        AppState s = new AppState();

        s.markCommandSentHr(110);
        Frame a = Protocol.parseLine("$D,SEQ=1,HRHI=110,SW=1,AL=0,*00");
        s.onFrame(a);
        T.ok("variant: hr threshold confirmed", s.isConfirmed(a));

        s.markCommandSentWater(25);
        Frame b = Protocol.parseLine("$D,SEQ=2,WLOW=25,SW=1,AL=0,*00");
        s.onFrame(b);
        T.ok("variant: water threshold confirmed", s.isConfirmed(b));

        // 水泵看的是 PMODE 而不是 PUMP —— 自动模式下水泵会随水位自己动，
        // 只盯 PUMP 会把"水位到了"误判成"命令生效了"
        s.markCommandSentPumpMode(2);
        Frame c = Protocol.parseLine("$D,SEQ=3,PUMP=1,PMODE=2,SW=1,AL=0,*00");
        s.onFrame(c);
        T.ok("variant: pump mode confirmed via PMODE", s.isConfirmed(c));
    }

    /** 数据新鲜度：超时必须能判定，界面据此置灰而不是把旧值当实时值。 */
    static void staleness() {
        AppState s = new AppState();
        Frame f = Protocol.parseLine("$D,SEQ=1,BT=36.5,SW=1,AL=0,*00");
        s.onFrame(f);
        s.noteFrameTime(1000L);

        T.eq("stale: threshold is 3000ms", AppState.STALE_FRAME_MS, 3000L);
        T.ok("stale: fresh at +0", !s.isStale(1000L));
        T.ok("stale: fresh at +2999", !s.isStale(3999L));
        T.ok("stale: stale at +3000", s.isStale(4000L));
        T.ok("stale: stale well after", s.isStale(60000L));

        // 还没收到过任何帧时不算"陈旧"（那叫"未连接"，界面文案不同）
        AppState empty = new AppState();
        T.ok("stale: no frame yet -> not stale", !empty.isStale(999999L));
    }

    /**
     * SW=0（拨动开关关掉）是用户主动行为，不是故障。
     * 必须能和"链路断了"区分开：前者界面显示"系统已停止"且不报警。
     */
    static void systemOff() {
        AppState s = new AppState();
        Frame off = Protocol.parseLine("$D,SEQ=1,SW=0,AL=0,*00");
        s.onFrame(off);

        T.ok("systemOff: detected", s.isSystemOff());
        T.ok("systemOff: frame has no body", !off.hasBody());
        T.ok("systemOff: last frame still recorded", s.last != null);

        // 系统关掉时数据不再更新，但那是"停止"不是"陈旧" —— 界面文案要分开
        s.noteFrameTime(1000L);
        T.ok("systemOff: still counts as stale by time (界面据此不再显示数值)",
             s.isStale(5000L));
    }

    /** 报警节流：1Hz 数据下同类报警 30 秒内只提示一次，否则会刷屏。 */
    static void alertThrottle() {
        AppState s = new AppState();
        T.eq("throttle: window is 30000ms", AppState.ALERT_REPEAT_MS, 30000L);

        T.ok("throttle: first fires", s.shouldAlert(Frame.AL_BT, 0L));
        T.ok("throttle: suppressed at +10s", !s.shouldAlert(Frame.AL_BT, 10000L));
        T.ok("throttle: suppressed at +29s", !s.shouldAlert(Frame.AL_BT, 29000L));
        T.ok("throttle: fires again at +31s", s.shouldAlert(Frame.AL_BT, 31000L));

        // 不同报警位各自独立计时，不能被别人压住
        T.ok("throttle: other bit independent", s.shouldAlert(Frame.AL_HR, 31000L));
    }
}
