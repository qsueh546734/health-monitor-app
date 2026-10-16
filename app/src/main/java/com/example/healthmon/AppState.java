package com.example.healthmon;

import java.util.HashMap;
import java.util.Map;

/**
 * 界面状态机：把"刚收到的一帧"翻译成"界面现在该显示什么"。
 *
 * 纯 Java、不 import android.* —— 所以它的每条判据都能用裸 javac 秒级验证。
 * 这里面全是"界面上看起来对、实际已经错了"的高发逻辑，值得单独测。
 */
public final class AppState {

    /** 超过这么久没有新帧，界面必须置灰并提示，而不是继续显示旧值 */
    public static final long STALE_FRAME_MS = 3000L;

    /** 同一类报警在这个窗口内只提示一次，否则 1Hz 数据会把界面刷爆 */
    public static final long ALERT_REPEAT_MS = 30000L;

    /** 最近一帧；没收到过任何帧时为 null */
    public Frame last;

    /** 收到过的帧总数。隐式 ACK 的基线就是它 —— 用计数而不是时间，避免时钟问题。 */
    public long recvCount = 0;

    private long lastFrameMs = 0;

    // ---- 待确认的命令 ----
    // 同一时刻只跟踪一条命令：界面上用户一次只点一个按钮，
    // 同时跟踪多条只会让"到底哪条没生效"变模糊。
    private long cmdBaseline = -1;
    private double wantBt = Double.NaN;
    private int wantHr = -1;
    private int wantWater = -1;
    private int wantPmode = -1;

    private final Map<Integer, Long> lastAlertAt = new HashMap<Integer, Long>();

    // ---------------------------------------------------------------- 收帧

    public void onFrame(Frame f) {
        if (f == null) return;
        last = f;
        recvCount++;
    }

    /**
     * 记录这一帧的到达时刻。
     *
     * 时刻由调用方注入（而不是在这里读 System.currentTimeMillis），
     * 这样测试可以不依赖真实时间 —— 否则"超时判定"这类测试会变成 flaky。
     */
    public void noteFrameTime(long nowMs) {
        lastFrameMs = nowMs;
    }

    /** 数据是否已经陈旧。注意是 >=，正好到 3000ms 就算陈旧。 */
    public boolean isStale(long nowMs) {
        if (last == null || lastFrameMs <= 0) return false;
        return (nowMs - lastFrameMs) >= STALE_FRAME_MS;
    }

    /** 拨动开关是否被关掉（用户主动行为，不是故障，不该报警） */
    public boolean isSystemOff() {
        return last != null && last.sw == 0;
    }

    // ---------------------------------------------------------------- 隐式 ACK

    /**
     * 记下"命令已发出"，并保存判定所需的期望值。
     *
     * 关键是 cmdBaseline = recvCount：只有【比它更新】的帧才允许判定确认。
     * 没有这个基线，残留状态会让"命令根本没发出去"显示成"已确认" ——
     * 上一项目的急停按钮就出过这个 bug。
     */
    public void markCommandSent(double expectedBtHi) {
        cmdBaseline = recvCount;
        wantBt = expectedBtHi;
        wantHr = -1;
        wantWater = -1;
        wantPmode = -1;
    }

    public void markCommandSentHr(int expectedHrHi) {
        cmdBaseline = recvCount;
        wantBt = Double.NaN;
        wantHr = expectedHrHi;
        wantWater = -1;
        wantPmode = -1;
    }

    public void markCommandSentWater(int expectedWLow) {
        cmdBaseline = recvCount;
        wantBt = Double.NaN;
        wantHr = -1;
        wantWater = expectedWLow;
        wantPmode = -1;
    }

    public void markCommandSentPumpMode(int expectedPmode) {
        cmdBaseline = recvCount;
        wantBt = Double.NaN;
        wantHr = -1;
        wantWater = -1;
        wantPmode = expectedPmode;
    }

    /** 是否已经可以判定命令生效。第一道门永远是"有没有新帧"。 */
    public boolean isConfirmed(Frame f) {
        if (cmdBaseline < 0 || f == null) return false;
        if (recvCount <= cmdBaseline) return false;          // 还没有新帧，谈不上确认

        if (!Double.isNaN(wantBt)) {
            return !Double.isNaN(f.btHi) && Math.abs(f.btHi - wantBt) < 0.05;
        }
        if (wantHr >= 0)    return f.hrHi == wantHr;
        if (wantWater >= 0) return f.wLow == wantWater;
        if (wantPmode >= 0) {
            // 必须看 PMODE 而不是 PUMP：自动模式下水泵本来就随水位自己动，
            // 盯 PUMP 会把"水位到了"误判成"命令生效了"
            return f.pmode == wantPmode;
        }
        return false;
    }

    public void clearCommand() {
        cmdBaseline = -1;
        wantBt = Double.NaN;
        wantHr = -1;
        wantWater = -1;
        wantPmode = -1;
    }

    // ---------------------------------------------------------------- 报警节流

    /**
     * 这一类报警现在该不该提示。同类 30 秒内只放行一次。
     *
     * 每个报警位独立计时：体温报警不该把心率报警压住。
     */
    public boolean shouldAlert(int alBit, long nowMs) {
        Long prev = lastAlertAt.get(alBit);
        if (prev != null && (nowMs - prev) < ALERT_REPEAT_MS) return false;
        lastAlertAt.put(alBit, nowMs);
        return true;
    }

    // ---------------------------------------------------------------- 复位

    public void reset() {
        last = null;
        recvCount = 0;
        lastFrameMs = 0;
        clearCommand();
        lastAlertAt.clear();
    }
}
