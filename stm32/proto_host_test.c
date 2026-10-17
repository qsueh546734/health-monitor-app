/*
 * proto_host_test.c —— 在电脑上把 proto.c 跑起来，抓它真实产生的帧
 *
 * 为什么要它：proto.c 是给同学的参考实现，而"它产生的字节"和"App 能不能解析"
 * 是两件独立的事。两边各自照文档实现，细节上错一位（逗号位置、小数位数、
 * 校验位写法）就会全盘不通，而这种错位只有把真实字节喂给真解析器才看得出来。
 *
 * 用法：
 *   gcc -std=c99 -Wall -Wextra proto.c proto_host_test.c -o proto_test
 *   ./proto_test
 *
 * 输出会被固化进 tools/tests/Stm32FormatTest.java 作为字面量。
 */
#include "proto.h"
#include <stdio.h>
#include <string.h>

/* ---- 抓取 proto.c 想发出去的字节 ---- */
static char  g_captured[512];
static int   g_capLen = 0;
static int   g_frameCount = 0;

void Proto_HwTx(const char* data, uint16_t len) {
    if (g_capLen + len < (int)sizeof(g_captured) - 1) {
        memcpy(g_captured + g_capLen, data, len);
        g_capLen += len;
        g_captured[g_capLen] = 0;
    }
    g_frameCount++;
}

/* 把抓到的内容按行打印，并标上标签 */
static void dump(const char* label) {
    printf("--- %s ---\n", label);
    int start = 0;
    for (int i = 0; i <= g_capLen; i++) {
        if (i == g_capLen || g_captured[i] == '\n') {
            if (i > start) {
                printf("%.*s\n", i - start, g_captured + start);
            }
            start = i + 1;
        }
    }
    g_capLen = 0;
    g_captured[0] = 0;
}

static Proto_Data baseData(void) {
    Proto_Data d;
    memset(&d, 0, sizeof(d));
    d.bt = 36.5f;  d.bt_valid = 1;
    d.hr = 72;
    d.spo2 = -1;
    d.steps = 1234;
    d.water = 68;
    d.pump = 0;
    d.pmode = 2;
    d.sw = 1;
    d.al = 0;
    return d;
}

int main(void) {
    Proto_Data d;

    printf("=== proto.c 实际产生的帧 ===\n\n");

    /* ---- 1. 正常帧 ---- */
    Proto_Init();
    d = baseData();
    Proto_Tick(1000, &d);
    dump("normal");

    /* ---- 2. 无效值（体温读不到、心率读不到） ---- */
    d.bt_valid = 0;
    d.hr = -1;
    Proto_Tick(2000, &d);
    dump("invalid-values");

    /* ---- 3. SW=0（拨动开关关掉） ---- */
    d = baseData();
    d.sw = 0;
    d.al = 0;
    Proto_Tick(3000, &d);
    dump("system-off");

    /* ---- 4. 收到 TH 命令后，下一帧的阈值必须变 ---- */
    Proto_Init();
    d = baseData();
    Proto_ParseCommand("$C,CMD=TH,BTHI=37.5,HRHI=105,WLOW=25,*00");
    Proto_Tick(1000, &d);
    dump("after-TH");

    /* ---- 5. 收到 PUMP MODE=1 后 ---- */
    d = baseData();
    Proto_ParseCommand("$C,CMD=PUMP,MODE=1,*00");
    d.pmode = Proto_GetPumpMode();
    d.pump = 1;
    Proto_Tick(2000, &d);
    dump("after-PUMP-manual-on");

    /* ---- 6. 越界阈值必须被钳制 ---- */
    Proto_Init();
    d = baseData();
    Proto_ParseCommand("$C,CMD=TH,BTHI=99.9,HRHI=999,WLOW=999,*00");
    Proto_Tick(1000, &d);
    dump("after-TH-clamped");

    /* ---- 7. 最长的一帧（检查是否超过 128 字节） ---- */
    Proto_Init();
    d = baseData();
    d.bt = 45.0f; d.hr = 220; d.spo2 = 100; d.steps = 999999;
    d.water = 100; d.pump = 1; d.pmode = 2; d.sw = 1; d.al = 15;
    /* 阈值不能从外部设 —— 它们是协议层内部状态。这里先把它们推到上限，
     * 让最坏长度真的把所有字段都撑满。 */
    Proto_ParseCommand("$C,CMD=TH,BTHI=42.0,HRHI=180,WLOW=100,*00");
    Proto_Tick(1000, &d);
    {
        int start = 0, longest = 0;
        for (int i = 0; i <= g_capLen; i++) {
            if (i == g_capLen || g_captured[i] == '\n') {
                if (i - start > longest) longest = i - start;
                start = i + 1;
            }
        }
        printf("--- worst-case-length ---\n");
        printf("len=%d (limit=128)\n", longest);
        dump("worst-case-frame");
    }

    /* ---- 8. QUERY 必须立刻补发 ---- */
    Proto_Init();
    d = baseData();
    Proto_Tick(1000, &d);          /* 正常一帧 */
    g_capLen = 0; g_captured[0] = 0;
    Proto_ParseCommand("$C,CMD=QUERY,*00");
    Proto_Tick(1000, &d);          /* 同一毫秒，应该因为 QUERY 而立即发 */
    dump("after-QUERY-immediate");

    /* ---- 9. $I 帧：IP 必须被提取出来 ---- */
    printf("--- info-frame ---\n");
    Proto_HandleInfoFrame("$I,IP=192.168.43.137,PORT=8266,MODE=STA,*00");
    printf("ip=%s\n", Proto_GetIp());

    /* ---- 10. 不以 $ 开头的行必须被忽略（ESP 的 # 调试行） ---- */
    printf("--- debug-lines-ignored ---\n");
    printf("parse(#AP started)=%d\n", Proto_ParseCommand("#AP started"));
    printf("parse(#client connected)=%d\n", Proto_ParseCommand("#client connected"));
    printf("parse($D,SEQ=1)=%d (STM32 自己发的帧不该被当命令)\n",
           Proto_ParseCommand("$D,SEQ=1,BT=36.5,*00"));

    printf("\n=== done ===\n");
    return 0;
}
