package com.example.healthmon;

/**
 * 模拟数据源：产出与真设备【同构】的 $D 帧。
 *
 * 它有两个不可替代的用途：
 *   1. 开发期 —— ESP8266 / STM32 还没就绪时，App 的全部界面都能开发完并验证
 *   2. 演示期 —— 现场网络出问题时切过去，仍能演示完整流程
 *
 * 因此它必须走【与网络完全相同的解析路径】（MainActivity.handleLine），
 * 而且产出的帧要能被同一个 Protocol.parseLine 接受。否则"模拟器能跑"
 * 只是假的安全感 —— 真机一接上全是问题。
 *
 * 刻意不用随机数：值由时间驱动，同样的 nowMs 永远得到同样的帧，
 * 出问题时可以精确复现。
 */
public final class Simulator {

    // 与真设备一致的默认阈值
    private static final double BTHI = 37.3;
    private static final int HRHI = 100;
    private static final int WLOW = 20;

    /** 水位周期：下降 200 秒（80% -> 15%），再花 60 秒被水泵补回 80% */
    private static final double WATER_DOWN_S = 200.0;
    private static final double WATER_UP_S = 60.0;
    private static final double WATER_HIGH = 80.0;
    private static final double WATER_LOW = 15.0;

    private static final double STEPS_PER_SEC = 1.8;

    private long t0 = -1;
    private int seq = 0;
    private int alMask = 0;
    private boolean systemOff = false;

    /** 演出用的"异常注入"：让演示者可以主动触发某一类报警 */
    public void setAnomaly(int alBit, boolean on) {
        if (on) alMask |= alBit;
        else alMask &= ~alBit;
    }

    /** 模拟拨动开关关掉 —— 会产出缺字段的帧，和真设备规则一致 */
    public void setSystemOff(boolean off) {
        this.systemOff = off;
    }

    /** 产出下一帧，返回【以 \n 结尾】的完整行。 */
    public String next(long nowMs) {
        if (t0 < 0) t0 = nowMs;
        double t = (nowMs - t0) / 1000.0;

        int thisSeq = seq;
        seq = (seq + 1) & 0xFFFF;

        // ---- SW=0：只发序号、开关、报警位，其余全省略（设计文档 §3.3）----
        if (systemOff) {
            return "$D,SEQ=" + thisSeq + ",SW=0,AL=0,*00\n";
        }

        boolean btAlarm = (alMask & Frame.AL_BT) != 0;
        boolean hrAlarm = (alMask & Frame.AL_HR) != 0;

        // 体温：正常时 36.2~36.8 缓慢游走；异常时升到 37.8 左右（必须真的越过阈值，
        // 否则演示时"点了没反应"）
        double bt = btAlarm
                ? 37.8 + 0.2 * Math.sin(t / 10.0)
                : 36.5 + 0.3 * Math.sin(t / 20.0);

        // 心率：正常 68~92；异常时 115 上下
        int hr = hrAlarm
                ? (int) Math.round(115 + 5 * Math.sin(t / 5.0))
                : (int) Math.round(80 + 12 * Math.sin(t / 7.0));

        long steps = (long) (STEPS_PER_SEC * t);

        // 水位：三角波，下降 200 秒、补水 60 秒
        double cycle = WATER_DOWN_S + WATER_UP_S;
        double phase = t % cycle;
        double water;
        if (phase < WATER_DOWN_S) {
            water = WATER_HIGH - (WATER_HIGH - WATER_LOW) * (phase / WATER_DOWN_S);
        } else {
            water = WATER_LOW + (WATER_HIGH - WATER_LOW) * ((phase - WATER_DOWN_S) / WATER_UP_S);
        }
        int waterPct = (int) Math.round(water);

        // 水泵：自动模式下水位低于阈值就开
        int pump = (waterPct < WLOW) ? 1 : 0;

        int al = alMask;
        if (waterPct < WLOW) al |= Frame.AL_WATER;

        StringBuilder sb = new StringBuilder(128);
        sb.append("$D,SEQ=").append(thisSeq);
        sb.append(",BT=").append(fmt1(bt));
        sb.append(",HR=").append(hr);
        sb.append(",SPO2=--");                 // 血氧 P2 前不做，固定发无效值
        sb.append(",STEPS=").append(steps);
        sb.append(",WATER=").append(waterPct);
        sb.append(",PUMP=").append(pump);
        sb.append(",PMODE=2");                 // 默认自动模式
        sb.append(",SW=1");
        sb.append(",AL=").append(al);
        sb.append(",BTHI=").append(fmt1(BTHI));
        sb.append(",HRHI=").append(HRHI);
        sb.append(",WLOW=").append(WLOW);
        sb.append(",*00\n");
        return sb.toString();
    }

    /** 固定 1 位小数 —— 协议规定的小数格式，别让模拟器坏了这个约定 */
    private static String fmt1(double v) {
        long scaled = Math.round(v * 10.0);
        return (scaled / 10) + "." + Math.abs(scaled % 10);
    }

    public void reset() {
        t0 = -1;
        seq = 0;
        alMask = 0;
        systemOff = false;
    }
}
