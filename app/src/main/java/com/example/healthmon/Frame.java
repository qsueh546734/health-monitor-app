package com.example.healthmon;

/**
 * 一帧上行数据（对应设计文档 §3.3 的 $D 帧）。
 *
 * 【哨兵约定】缺失或无效的字段一律用 NaN / -1 表示，绝不用 0。
 * 因为 0 是有意义的物理量（0°C、0 bpm 都是"读到了但值很离谱"），
 * 而 NaN / -1 表示"这一帧里根本没有这个字段"或"传感器没读出来"。
 * 界面据此显示 "--"，绝不能显示 0。
 *
 * 这个约定贯穿 Db（读库）、MainActivity（渲染）、ChartView（画图）、AiClient（摘要），
 * 任何一处改成 0 都会让"没读到"看起来像"读到了 0"。
 */
public final class Frame {

    public int seq = -1;              // 帧序号
    public long ts = 0;               // 【本地】到达时刻（毫秒）。上行帧里没有这个字段，
                                      // 由接收方记录，供历史存储与曲线使用。
    public double bt = Double.NaN;    // 体温 ℃（GY-906）
    public int hr = -1;               // 心率 bpm（MAX30102）
    public int spo2 = -1;             // 血氧 %（MAX30102，P2 前恒为 -1）
    public long steps = -1;           // 累计步数（MPU6050）
    public int water = -1;            // 水位 %
    public int pump = -1;             // 水泵【实际】状态 0/1
    public int pmode = -1;            // 水泵【模式】 0=手动关 1=手动开 2=自动
    public int sw = -1;               // 拨动开关 1=系统运行 0=系统停止
    public int al = 0;                // 报警位域，见 §3.4
    public double btHi = Double.NaN;  // 阈值回读
    public int hrHi = -1;
    public int wLow = -1;

    // 报警位，便于调用方按名字用，避免到处写魔法数
    public static final int AL_BT = 0x01;
    public static final int AL_HR = 0x02;
    public static final int AL_WATER = 0x04;
    public static final int AL_FAULT = 0x08;

    /** 这一帧是否带着传感器数据。SW=0 的帧不带，界面据此整体置灰。 */
    public boolean hasBody() {
        return !Double.isNaN(bt) || hr >= 0 || water >= 0 || steps >= 0;
    }

    /** 系统是否被拨动开关关掉了 */
    public boolean isSystemOff() {
        return sw == 0;
    }
}
