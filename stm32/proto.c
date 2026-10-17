/**
 * proto.c —— STM32 与手机 App 的通信协议（参考实现）
 *
 * 纯标准 C，不依赖 HAL / 标准库之外的任何东西。
 * 协议细节见同目录 STM32侧开发说明.md。
 */
#include "proto.h"
#include <string.h>

/* ================================================================== 内部状态 */

static volatile uint8_t  s_rx[PROTO_RX_RING];
static volatile uint16_t s_rxHead = 0;      /* 中断里写 */
static volatile uint16_t s_rxTail = 0;      /* 主循环里读 */

static char     s_line[PROTO_LINE_MAX];
static uint16_t s_lineLen = 0;

static uint32_t s_seq = 0;
static uint32_t s_lastSendMs = 0;

/* 阈值：上电给一组安全的默认值 */
static float s_btHi = 37.3f;
static int   s_hrHi = 100;
static int   s_wLow = 20;

/* 水泵模式：上电默认自动 */
static int s_pmode = PMODE_AUTO;

/* 累计步数（由外部通过 Proto_ClearSteps 清零） */
static uint32_t s_steps = 0;

/* ESP 报过来的 IP */
static char s_ip[20] = {0};

/* ================================================================== 工具 */

static int clamp_int(int v, int lo, int hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

static float clamp_float(float v, float lo, float hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

/** 把 scaled 值写成 "d.d" 形式（scaled 是放大 10 倍后的整数） */
static int num_to_str(char* out, long scaled) {
    long ip = scaled / 10;
    long fp = scaled % 10;
    int n = 0;
    char tmp[16];
    int m = 0;

    if (ip == 0) {
        tmp[m++] = '0';
    } else {
        while (ip > 0) { tmp[m++] = (char)('0' + (ip % 10)); ip /= 10; }
    }
    while (m > 0) out[n++] = tmp[--m];
    out[n++] = '.';
    out[n++] = (char)('0' + fp);
    return n;
}

/** 浮点转 "36.5" 这种 1 位小数，用整数运算避开浮点 printf。
 *  注意 num_to_str 必须定义在它前面 —— 否则会变成隐式声明 + 静态声明冲突。 */
static int fmt1(char* out, float v) {
    int neg = 0;
    long scaled;
    if (v < 0) { neg = 1; v = -v; }
    scaled = (long)(v * 10.0f + 0.5f);        /* 四舍五入 */
    if (neg) {
        out[0] = '-';
        return 1 + num_to_str(out + 1, scaled);
    }
    return num_to_str(out, scaled);
}

static int u32_to_str(char* out, uint32_t v) {
    char tmp[12];
    int m = 0, n = 0;
    if (v == 0) { out[0] = '0'; return 1; }
    while (v > 0) { tmp[m++] = (char)('0' + (v % 10)); v /= 10; }
    while (m > 0) out[n++] = tmp[--m];
    return n;
}

/* ================================================================== 初始化 */

void Proto_Init(void) {
    s_rxHead = s_rxTail = 0;
    s_lineLen = 0;
    s_seq = 0;
    s_lastSendMs = 0;
    s_btHi = 37.3f;
    s_hrHi = 100;
    s_wLow = 20;
    s_pmode = PMODE_AUTO;
    s_steps = 0;
    s_ip[0] = 0;
}

/* ================================================================== 接收 */

void Proto_RxByte(uint8_t b) {
    uint16_t next = (uint16_t)((s_rxHead + 1) & (PROTO_RX_RING - 1));
    if (next != s_rxTail) {                  /* 满了就丢新字节，不覆盖旧数据 */
        s_rx[s_rxHead] = b;
        s_rxHead = next;
    }
}

int Proto_Poll(char* out, uint16_t max) {
    while (s_rxTail != s_rxHead) {
        uint8_t b = s_rx[s_rxTail];
        s_rxTail = (uint16_t)((s_rxTail + 1) & (PROTO_RX_RING - 1));

        if (b == '\n') {
            if (s_lineLen == 0) continue;    /* 空行 */
            if (s_lineLen < max) {
                memcpy(out, s_line, s_lineLen);
                out[s_lineLen] = 0;
            } else {
                s_lineLen = 0;
                continue;                    /* 太长，丢掉 */
            }
            s_lineLen = 0;
            return 1;
        }
        if (b == '\r') continue;             /* 容错剥离 \r */

        if (s_lineLen < PROTO_LINE_MAX - 1) {
            s_line[s_lineLen++] = (char)b;
        } else {
            s_lineLen = 0;                   /* 溢出：丢掉这一行 */
        }
    }
    return 0;
}

/* ================================================================== 命令解析 */

/** 在一行里找 "KEY=value"，把 value 拷进 out。返回 1 = 找到。 */
static int get_field(const char* line, const char* key, char* out, uint16_t outMax) {
    size_t klen = strlen(key);
    const char* p = line;

    while ((p = strchr(p, '=')) != 0) {
        /* p 往前找 key 的起点 */
        if ((size_t)(p - line) >= klen) {
            const char* start = p - klen;
            if (strncmp(start, key, klen) == 0) {
                /* 确认前面是行首、',' 或 '$' */
                if (start == line || *(start - 1) == ',' || *(start - 1) == '$') {
                    const char* v = p + 1;
                    uint16_t n = 0;
                    while (*v && *v != ',' && *v != '*' && n + 1 < outMax) {
                        out[n++] = *v++;
                    }
                    out[n] = 0;
                    return 1;
                }
            }
        }
        p++;                                  /* 找下一个 '=' */
    }
    return 0;
}

/** 把 "37.3" 解析成 373（放大 10 倍的整数），避开浮点库。返回 1 = 成功。 */
static int parse_f1(const char* s, int* outScaled) {
    int neg = 0, ip = 0, fp = 0, seenDot = 0, digits = 0;
    if (!s || !*s) return 0;
    if (*s == '-') { neg = 1; s++; }
    while (*s) {
        if (*s == '.') { seenDot = 1; s++; continue; }
        if (*s < '0' || *s > '9') return 0;
        if (!seenDot) { ip = ip * 10 + (*s - '0'); }
        else if (digits == 0) { fp = (*s - '0'); digits = 1; }
        s++;
    }
    *outScaled = ip * 10 + fp;
    if (neg) *outScaled = -*outScaled;
    return 1;
}

static int parse_int(const char* s, int* out) {
    int neg = 0, v = 0;
    if (!s || !*s) return 0;
    if (*s == '-') { neg = 1; s++; }
    while (*s) {
        if (*s < '0' || *s > '9') return 0;
        v = v * 10 + (*s - '0');
        s++;
    }
    *out = neg ? -v : v;
    return 1;
}

int Proto_ParseCommand(const char* line) {
    char cmd[16];
    char v[24];
    int scaled, iv;

    if (!line || line[0] != '$' || line[1] != 'C') return 0;
    if (!get_field(line, "CMD", cmd, sizeof(cmd))) return 0;

    /* ---- TH：改阈值 ---- */
    if (strcmp(cmd, "TH") == 0) {
        if (get_field(line, "BTHI", v, sizeof(v)) && parse_f1(v, &scaled)) {
            float f = scaled / 10.0f;
            s_btHi = clamp_float(f, 35.0f, 42.0f);
        }
        if (get_field(line, "HRHI", v, sizeof(v)) && parse_int(v, &iv)) {
            s_hrHi = clamp_int(iv, 60, 180);
        }
        if (get_field(line, "WLOW", v, sizeof(v)) && parse_int(v, &iv)) {
            s_wLow = clamp_int(iv, 0, 100);
        }
        return 1;
    }

    /* ---- PUMP：切换水泵模式 ---- */
    if (strcmp(cmd, "PUMP") == 0) {
        if (get_field(line, "MODE", v, sizeof(v)) && parse_int(v, &iv)) {
            if (iv == 0 || iv == 1 || iv == 2) s_pmode = iv;
        }
        return 1;
    }

    /* ---- QUERY：立即补发一帧。实际发送在主循环里做 ---- */
    if (strcmp(cmd, "QUERY") == 0) {
        s_lastSendMs = 0;                     /* 让 Proto_Tick 马上发 */
        return 1;
    }

    /* ---- CLRSTEPS：步数清零 ---- */
    if (strcmp(cmd, "CLRSTEPS") == 0) {
        s_steps = 0;
        return 1;
    }

    /* WIFI / WIFIFORGET / WIFISTATUS 会被 ESP 拦截，根本到不了这里 */
    return 0;
}

/* ================================================================== $I 帧 */

int Proto_HandleInfoFrame(const char* line) {
    char v[24];
    if (!line || line[0] != '$' || line[1] != 'I') return 0;
    if (get_field(line, "IP", v, sizeof(v))) {
        strncpy(s_ip, v, sizeof(s_ip) - 1);
        s_ip[sizeof(s_ip) - 1] = 0;
    }
    return 1;
}

const char* Proto_GetIp(void) { return s_ip; }

/* ================================================================== 发送 */

static const char* mode_str(int m) {
    if (m == PMODE_MANUAL_OFF) return "0";
    if (m == PMODE_MANUAL_ON) return "1";
    return "2";
}

void Proto_Tick(uint32_t now_ms, const Proto_Data* d) {
    char buf[160];
    int n = 0;
    int k;

    if (!d) return;
    if (s_lastSendMs != 0 && (uint32_t)(now_ms - s_lastSendMs) < PROTO_REPORT_MS) return;
    s_lastSendMs = now_ms;

    /* ---------- SW=0：只发三个字段 ----------
     * 仍然每秒发一帧，不能停发 —— 停发的话 App 分不清
     * "用户主动关机" 和 "WiFi 断了"，后者要报警、前者不该。 */
    if (d->sw == 0) {
        memcpy(buf, "$D,SEQ=", 7); n = 7;
        n += u32_to_str(buf + n, s_seq);
        /* 这串字面量是 15 个字符（含末尾 \n），不是 16 —— 早先写成 17 会越界读 */
        memcpy(buf + n, ",SW=0,AL=0,*00\n", 15); n += 15;
        Proto_HwTx(buf, (uint16_t)n);
        s_seq = (s_seq + 1) & 0xFFFF;
        return;
    }

    /* ---------- 正常帧 ---------- */
    memcpy(buf, "$D,SEQ=", 7);
    n = 7;
    n += u32_to_str(buf + n, s_seq);

    memcpy(buf + n, ",BT=", 4); n += 4;
    if (d->bt_valid && d->bt > 0.0f) {
        n += fmt1(buf + n, d->bt);
    } else {
        memcpy(buf + n, "--", 2); n += 2;
    }

    memcpy(buf + n, ",HR=", 4); n += 4;
    if (d->hr >= 0) n += u32_to_str(buf + n, (uint32_t)d->hr);
    else { memcpy(buf + n, "--", 2); n += 2; }

    memcpy(buf + n, ",SPO2=", 6); n += 6;
    if (d->spo2 >= 0) n += u32_to_str(buf + n, (uint32_t)d->spo2);
    else { memcpy(buf + n, "--", 2); n += 2; }

    memcpy(buf + n, ",STEPS=", 7); n += 7;
    n += u32_to_str(buf + n, d->steps);

    memcpy(buf + n, ",WATER=", 7); n += 7;
    k = clamp_int(d->water, 0, 100);
    n += u32_to_str(buf + n, (uint32_t)k);

    memcpy(buf + n, ",PUMP=", 6); n += 6;
    buf[n++] = (char)('0' + (d->pump ? 1 : 0));

    memcpy(buf + n, ",PMODE=", 7); n += 7;
    memcpy(buf + n, mode_str(d->pmode), 1); n += 1;

    memcpy(buf + n, ",SW=", 4); n += 4;
    buf[n++] = (char)('0' + (d->sw ? 1 : 0));

    memcpy(buf + n, ",AL=", 4); n += 4;
    n += u32_to_str(buf + n, (uint32_t)(d->al & 0x0F));

    /* ---- 三个阈值必须回显：App 靠它判断命令是否生效 ---- */
    memcpy(buf + n, ",BTHI=", 6); n += 6;
    n += fmt1(buf + n, s_btHi);

    memcpy(buf + n, ",HRHI=", 6); n += 6;
    n += u32_to_str(buf + n, (uint32_t)s_hrHi);

    memcpy(buf + n, ",WLOW=", 6); n += 6;
    n += u32_to_str(buf + n, (uint32_t)s_wLow);

    memcpy(buf + n, ",*00\n", 5); n += 5;

    /* 长度守卫：超了就说明组装有 bug，宁可不发也不要发一条超长的 */
    if (n > PROTO_FRAME_MAX) return;

    Proto_HwTx(buf, (uint16_t)n);
    s_seq = (s_seq + 1) & 0xFFFF;
}

/* ================================================================== 查询 */

float Proto_GetBtHi(void)   { return s_btHi; }
int   Proto_GetHrHi(void)   { return s_hrHi; }
int   Proto_GetWLow(void)   { return s_wLow; }
int   Proto_GetPumpMode(void) { return s_pmode; }
uint32_t Proto_GetSteps(void) { return s_steps; }
void  Proto_ClearSteps(void)  { s_steps = 0; }
