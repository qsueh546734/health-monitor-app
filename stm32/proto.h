/**
 * proto.h —— STM32 与手机 App 的通信协议（参考实现）
 *
 * 自包含：不依赖任何外部库，只要标准 C。
 * 详细协议说明见同目录 STM32侧开发说明.md。
 *
 * 【设计取舍】
 *  - 不发浮点 printf：体温用整数运算拼成 "36.5"，避免拖进浮点格式化库
 *  - 单帧缓冲 160 字节：协议上限 128，留余量但不无限大
 *  - 接收用环形缓冲 + 在主循环里切行：中断里只存字节，不解析
 */
#ifndef PROTO_H
#define PROTO_H

#include <stdint.h>

/* ---- 协议常量 ---- */
#define PROTO_LINE_MAX      192     /* 单行缓冲：协议上限 128，留余量 */
#define PROTO_RX_RING       256     /* 接收环形缓冲大小，必须是 2 的幂 */
#define PROTO_REPORT_MS     1000    /* 上报周期：1 秒 1 帧 */
#define PROTO_FRAME_MAX     128     /* 单帧长度上限（协议规定） */

/* ---- 报警位 ---- */
#define AL_BT       0x01
#define AL_HR       0x02
#define AL_WATER    0x04
#define AL_FAULT    0x08

/* ---- 水泵模式 ---- */
#define PMODE_MANUAL_OFF  0
#define PMODE_MANUAL_ON   1
#define PMODE_AUTO        2

/* ---- 无效标记：体温用这个表示"没读到" ---- */
#define BT_INVALID   (-1000)        /* 用 -1000 表示 NaN，避免引入浮点比较 */

/** 一帧要上报的全部数据。由你的代码填，Proto_Tick 负责组装发送。 */
typedef struct {
    float    bt;          /* 体温 ℃；无效时填 BT_INVALID_AS_FLOAT，见下 */
    int      bt_valid;    /* 0 = 体温无效（发 --）；1 = 有效 */
    int      hr;          /* 心率 bpm；< 0 表示无效 */
    int      spo2;        /* 血氧 %；< 0 表示无效（本项目暂时恒为无效） */
    uint32_t steps;       /* 累计步数 */
    int      water;       /* 水位 %，0-100 */
    int      pump;        /* 水泵实际状态 0/1 */
    int      pmode;       /* 水泵模式 0/1/2 */
    int      sw;          /* 拨动开关 1=运行 0=停止 */
    int      al;          /* 报警位域，见 AL_xxx */

    /* 注意：这里【没有】阈值字段。
     * BTHI/HRHI/WLOW 由协议层自己持有 —— 它们只通过 $C,CMD=TH 改变，
     * 并且会被钳制到合法范围。让调用方再填一遍只会引入两个真相，
     * 而且填错了会导致 App 一直显示"等待设备确认"。
     * 想读当前阈值用 Proto_GetBtHi() / Proto_GetHrHi() / Proto_GetWLow()。 */
} Proto_Data;

/* ==================================================================
 * 你必须实现的硬件接口
 * ================================================================== */

/** 把 len 字节从 USART2 发出去。由你在 main.c 里实现。 */
void Proto_HwTx(const char* data, uint16_t len);

/* ==================================================================
 * 协议接口
 * ================================================================== */

/** 初始化。上电调用一次。 */
void Proto_Init(void);

/** 【在 USART2 的接收中断里调用】把收到的字节塞进环形缓冲。 */
void Proto_RxByte(uint8_t b);

/**
 * 【在主循环里调用】取出一整行（不含换行符）。
 * 返回 1 = 取到一行，0 = 暂时没有完整行。
 */
int Proto_Poll(char* out, uint16_t max);

/**
 * 【在主循环里调用】解析 App 下发的命令。
 * 只处理以 '$' 开头的行；其余（如 ESP 的 '#' 调试行）自动忽略。
 * 返回值：1 = 是命令且处理了，0 = 不是给本机的命令。
 */
int Proto_ParseCommand(const char* line);

/**
 * 【在主循环里调用】处理 ESP 发来的 $I 帧，把 IP 提取出来。
 * 返回值：1 = 是 $I 帧，0 = 不是。
 */
int Proto_HandleInfoFrame(const char* line);

/** 上电后最近一次从 $I 帧里拿到的 IP 字符串（如 "192.168.4.1"）。没拿到时是空串。 */
const char* Proto_GetIp(void);

/**
 * 【在主循环里调用，每次循环都调】到 1 秒就发一帧。
 * now_ms 传你的毫秒计数（SysTick 累加值）。
 */
void Proto_Tick(uint32_t now_ms, const Proto_Data* d);

/** 查询过滤后的当前阈值（供你的报警判断用） */
float Proto_GetBtHi(void);
int   Proto_GetHrHi(void);
int   Proto_GetWLow(void);
int   Proto_GetPumpMode(void);
uint32_t Proto_GetSteps(void);
void  Proto_ClearSteps(void);

#endif /* PROTO_H */
