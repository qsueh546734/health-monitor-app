/*
 * ============================================================================
 *  长期健康监测系统 —— ESP8266-01S 通信固件  v2
 * ============================================================================
 *
 *  作用：把 STM32 的串口和手机的 TCP 连接对接起来，并支持从 App 配置 WiFi。
 *
 *      STM32 ──串口 115200── ESP8266 ──WiFi── 手机 App
 *
 *  组网策略：
 *      上电 → 从 Flash 读已保存的 WiFi 凭据
 *           → 有凭据：尝试连路由器（STA），10 秒超时
 *                → 成功：MODE=STA
 *                → 失败：MODE=AP（自己开热点）
 *           → 没凭据：直接 MODE=AP
 *
 *      安全兜底：AP 模式下永远能连上。就算配错了 WiFi，
 *      断电重启 → STA 连不上 → 10 秒后自动回到 AP，不会把自己锁死。
 *
 *  串口协议：
 *      ESP → STM32 :  $I,IP=...,PORT=...,MODE=...,*00      状态帧
 *      ESP → STM32 :  原样透传手机发来的字节（WiFi 配置命令除外）
 *      STM32 → ESP :  原样透传给手机的字节
 *      ESP → STM32 :  # 开头的调试行（STM32 必须忽略）
 *
 *  App 可用的 ESP 专属命令（会被 ESP 拦截，不转发给 STM32）：
 *      $C,CMD=WIFI,SSID=xxx,PASS=yyy,*00    配置并连接路由器
 *      $C,CMD=WIFIFORGET,*00                清空凭据，回到热点模式
 *      $C,CMD=WIFISTATUS,*00                查询当前状态
 *
 *  SSID 和密码里不能有逗号 ','（会破坏 Key=Value 解析）
 *
 *  编译目标：Arduino IDE → 开发板 "Generic ESP8266 Module"
 *           Flash Size 1MB (FS:none OTA:~370KB) / Upload Speed 115200
 * ============================================================================
 */

#include <ESP8266WiFi.h>

// ⚠️ 这里【故意不用】Arduino 的 EEPROM 库。
//
// 实测（2026-10-15）：在这块 ESP-01S 上，EEPROM 写入后立刻回读是对的，
// 但 ESP.restart() 之后 `#EEPROM: not initialised` —— 数据没落到 flash。
// 原因是 Arduino EEPROM 用的 flash 扇区会和 SDK 自己的系统参数/RF 校准区冲突，
// 被开机流程覆盖掉。
//
// 改用 ESP8266 SDK 自带的 WiFi 持久化（WiFi.persistent + WiFi.begin），
// 那是专门为"保存 WiFi 凭据"设计的机制，由 SDK 自己管理，不会冲突。

// ============================== 可配置项 ==============================

#define AP_SSID        "HealthMon"
#define AP_PASS        "12345678"      // 至少 8 位，否则 softAP 会失败
#define TCP_PORT       8266

// ---- 连不上就开热点（这是"永远能连上设备"的最终保障）----
//
// 现场没有路由器、手机热点又出问题时靠它：累计尝试 STA_GIVEUP_MS 还连不上，
// 就自己开热点，让用户至少能连上设备把数据导出来、或者重新配网。
//
// 开机时的两次尝试（已保存凭据 / 兜底热点）共享这同一份预算，总共不超过它。
#define STA_GIVEUP_MS   20000UL

#define STA_SAVED_MS    10000UL        // 单次尝试上限：SDK 里保存的凭据
#define STA_LOST_MS     20000UL        // 运行中掉线多久后开自己的热点

#define FLUSH_MS        300            // 切换模式前，留给应答帧发出去的时间

// 调试输出：1 = 往串口打印 '#' 开头的人类可读状态行
// STM32 的解析器应当忽略不以 '$' 开头的行；如果你的解析器不忽略，改成 0
#define SERIAL_DEBUG    1

#define SSID_MAX      32
#define PASS_MAX      64

// ---- 兜底 WiFi ----
//
// SDK 里没有保存过凭据时（比如第一次烧录后），用下面这组。
// 现场热点固定不变时，这是【最可靠】的路径 —— 不依赖任何持久化机制。
//
// 想改成"只认 App 下发的凭据"：把 USE_DEFAULT_WIFI 设成 0 再烧录。
// 注意：开着兜底时，【忘记网络】会退回连这个默认热点而不是回到 AP 模式。
#define USE_DEFAULT_WIFI 1
static const char* DEFAULT_SSID = "iKUN 13";
static const char* DEFAULT_PASS = "99935gzyyzxq";

// 单行缓冲：协议规定单帧 <= 128 字节，给一倍余量
#define LINE_BUF_SIZE 192

// ============================== 内部状态 ==============================

enum NetMode { MODE_STA, MODE_AP };

static NetMode    g_mode = MODE_AP;
static WiFiServer g_server(TCP_PORT);
static WiFiClient g_client;
static bool       g_clientWasUp = false;   // 不能用 g_client 本身判断：operator bool() 就是 connected()
static uint32_t   g_staDownSince = 0;

// 当前使用的凭据【运行时副本】，只用于状态判断和上报。
// 真正的持久化在 SDK 那边（见文件顶部说明）。
static char       g_savedSsid[SSID_MAX + 1] = {0};
static char       g_savedPass[PASS_MAX + 1] = {0};

// 上次开机连 STA 失败的原因码。非空时，会在 App 下次连上后报给它。
static char       g_lastStaFail[12] = {0};

// TCP → 串口方向的按行缓冲（只为了拦截 WiFi 命令；数据率很低，无性能影响）
static char       g_line[LINE_BUF_SIZE];
static uint16_t   g_lineLen = 0;

// ============================== 调试 ==============================

static void debug(const char* msg) {
#if SERIAL_DEBUG
  Serial.print('#');
  Serial.println(msg);
#else
  (void)msg;
#endif
}

// ============================== WiFi 凭据 ==============================
//
// 持久化交给 SDK。这里只维护一份运行时副本，供状态判断用。

/** 记住当前使用的凭据（仅内存） */
static void credentialsRemember(const char* ssid, const char* pass) {
  strncpy(g_savedSsid, ssid ? ssid : "", SSID_MAX); g_savedSsid[SSID_MAX] = '\0';
  strncpy(g_savedPass, pass ? pass : "", PASS_MAX); g_savedPass[PASS_MAX] = '\0';
}

/** 清掉运行时副本 */
static void credentialsForget() {
  g_savedSsid[0] = '\0';
  g_savedPass[0] = '\0';
}

// ============================== 状态帧 ==============================

static IPAddress localAddr() {
  return (g_mode == MODE_STA) ? WiFi.localIP() : WiFi.softAPIP();
}

/**
 * 发送状态帧。同时发给两侧：
 *   - STM32（串口）-> 显示在 OLED 上
 *   - App（TCP）   -> 显示当前模式/IP，并作为 WiFi 配置命令的应答
 * extra 形如 ",WIFI=OK" 或 ",WIFI=FAIL,RSN=PWD"，可为 NULL。
 */
static void sendInfoFrame(const char* extra) {
  IPAddress ip = localAddr();
  char buf[176];
  snprintf(buf, sizeof(buf),
           "$I,IP=%u.%u.%u.%u,PORT=%d,MODE=%s%s,*00\n",
           ip[0], ip[1], ip[2], ip[3], TCP_PORT,
           (g_mode == MODE_STA) ? "STA" : "AP",
           extra ? extra : "");
  Serial.print(buf);
  if (g_client.connected()) {
    g_client.print(buf);
  }
}

static void sendInfoFrame() { sendInfoFrame(NULL); }

// ============================== 组网 ==============================

/** 把 WiFi.status() 翻译成 App 能看懂的短原因码 */
static const char* statusReason(int st) {
  switch (st) {
    case WL_NO_SSID_AVAIL:   return "NOSID";     // 找不到这个 WiFi（名字错或不在范围）
    case WL_CONNECT_FAILED:  return "CONNFAIL";  // 连接失败
    case WL_WRONG_PASSWORD:  return "PWD";       // 密码错
    default:                 return "TIMEOUT";   // 超时
  }
}

/**
 * 尝试连接。成功返回 true。
 *
 * ssid 传 NULL 或空串 = 使用 SDK 里【已保存的】配置（不开参数）。
 *
 * 注意：这里一定会切换 WiFi 模式，而切模式会重配 softAP、踢掉已连上的 TCP 客户端。
 * 所以【绝不能在持有客户端连接时调用它】—— 只能在开机阶段用。
 * App 下发配置走的是"回复 -> 交给 SDK 保存 -> 重启"（见 handleWifiSet）。
 */
static bool connectSTA(const char* ssid, const char* pass, uint32_t timeoutMs) {
  WiFi.mode(WIFI_STA);
  WiFi.setSleepMode(WIFI_NONE_SLEEP);   // 关省电：避免延迟抖动和 TCP 掉线

  if (ssid && ssid[0] != '\0') {
    WiFi.begin(ssid, pass ? pass : "");
  } else {
    WiFi.begin();                       // 无参数：用 SDK 已保存的凭据
  }

  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED) {
    if (millis() - start > timeoutMs) {
      return false;
    }
    delay(100);
  }
  return true;
}

static void startAP() {
  debug("starting AP...");
  WiFi.mode(WIFI_AP);
  WiFi.setSleepMode(WIFI_NONE_SLEEP);   // AP 模式同样关省电
  if (!WiFi.softAP(AP_SSID, AP_PASS)) {
    // 最常见原因：密码不足 8 位
    debug("softAP FAILED (password < 8 chars?)");
  }
  g_mode = MODE_AP;
  debug("AP started");
}

static void startServer() {
  g_server.begin();
  g_server.setNoDelay(true);
  debug("TCP server started");
}

/** 重启 TCP 服务（切换 WiFi 模式后必须做，否则监听会失效） */
static void restartServer() {
  g_server.stop();
  if (g_clientWasUp) { g_client.stop(); g_clientWasUp = false; }
  g_lineLen = 0;
  startServer();
}

// ============================== 命令行解析小工具 ==============================

/** 判断这行是不是某个 CMD（精确匹配，避免 WIFI 误配 WIFIFORGET） */
static bool cmdIs(const char* line, const char* name) {
  const char* p = strstr(line, "CMD=");
  if (!p) return false;
  p += 4;
  size_t n = strlen(name);
  if (strncmp(p, name, n) != 0) return false;
  char after = p[n];
  return (after == ',' || after == '*' || after == '\0');
}

/** 取出 KEY=value 里的 value，写到 out。找不到返回 false */
static bool getField(const char* line, const char* key, char* out, size_t outSize) {
  char pat[24];
  snprintf(pat, sizeof(pat), "%s=", key);
  const char* p = strstr(line, pat);
  if (!p) return false;

  // 防止把 "XSSID=" 误认成 "SSID="：前面必须是行首、',' 或 '$'
  if (p != line) {
    char prev = *(p - 1);
    if (prev != ',' && prev != '$') return false;
  }

  p += strlen(pat);
  size_t i = 0;
  while (*p && *p != ',' && *p != '*' && i + 1 < outSize) {
    out[i++] = *p++;
  }
  out[i] = '\0';
  return true;
}

// ============================== WiFi 配置命令 ==============================

/** 回答 App：配置结果 */
static void replyWifi(const char* result, const char* reason) {
  char extra[48];
  if (reason) snprintf(extra, sizeof(extra), ",WIFI=%s,RSN=%s", result, reason);
  else        snprintf(extra, sizeof(extra), ",WIFI=%s", result);
  sendInfoFrame(extra);
}

static void handleWifiSet(const char* line) {
  char ssid[SSID_MAX + 1] = {0};
  char pass[PASS_MAX + 1] = {0};

  if (!getField(line, "SSID", ssid, sizeof(ssid)) || ssid[0] == '\0') {
    debug("WIFI: SSID missing");
    replyWifi("FAIL", "NOSSI");
    return;
  }
  getField(line, "PASS", pass, sizeof(pass));   // 开放网络允许空密码

  // 诊断输出：确认 ESP 到底解析出了什么。
  // SSID 里有空格、被截断、多带了字符等问题，看这一行就一目了然。
  Serial.print(F("#parsed SSID=[")); Serial.print(ssid);
  Serial.print(F("] len=")); Serial.print(strlen(ssid));
  Serial.print(F("  PASS len=")); Serial.println(strlen(pass));

  // 【不在原地尝试连接】—— 这是修掉的那个 bug。
  //
  // 原地连接必须先切 WiFi 模式（AP -> AP_STA/STA），而切模式会重配 softAP，
  // 已连上的 TCP 客户端会被立刻踢掉：用户既看不到 "TRYING"，也看不到失败原因，
  // 只看到"发出去就断线"。
  //
  // 改成：保存 -> 重启 -> 由开机流程去连。
  //   连上了   -> 热点消失，设备出现在新网络里
  //   没连上   -> 10 秒后回到 AP 模式，并在 App 下次连上时把失败原因报回去
  replyWifi("SAVED", NULL);
  delay(FLUSH_MS);            // 先让应答发出去 —— 下面一动 WiFi 就会踢掉当前连接

  // 交给 SDK 持久化：persistent(true) + begin(ssid,pass) 会把凭据写进
  // SDK 自己的系统参数扇区，开机时 SDK 自动使用它。
  WiFi.persistent(true);
  WiFi.begin(ssid, pass);
  delay(1000);                // 给 SDK 时间把配置写进 flash

  credentialsRemember(ssid, pass);
  debug("handed to SDK, restarting");
  ESP.restart();
}

static void handleWifiForget() {
  replyWifi("FORGOTTEN", NULL);
  delay(FLUSH_MS);
  credentialsForget();
  ESP.eraseConfig();          // 抹掉 SDK 保存的 WiFi 配置
  debug("erased SDK config, restarting");
  ESP.restart();
}

static void handleWifiStatus() {
  sendInfoFrame();
}

/** 这行是不是 ESP 自己的命令？是就处理掉并返回 true（不转发给 STM32） */
static bool handleEspCommand(const char* line) {
  // 只认 App 下发的 $C 命令；$D 是 STM32 自己发的，不会从这个方向来
  if (line[0] != '$' || line[1] != 'C') return false;

  if (cmdIs(line, "WIFI"))       { handleWifiSet(line);   return true; }
  if (cmdIs(line, "WIFIFORGET")) { handleWifiForget();    return true; }
  if (cmdIs(line, "WIFISTATUS")) { handleWifiStatus();    return true; }
  return false;
}

// ============================== TCP 接收（按行） ==============================

static void handleTcpLine(const char* line, uint16_t len) {
  if (handleEspCommand(line)) {
    debug("(intercepted ESP command)");
    return;                                   // 拦截：不给 STM32
  }
  // 其余原样转发给 STM32（补回被我们切掉的行尾）
  Serial.write((const uint8_t*)line, len);
  Serial.write('\n');
}

/** 把缓冲区里的一行交给处理，并清空 */
static void flushTcpLine() {
  if (g_lineLen == 0) return;
  g_line[g_lineLen] = '\0';
  handleTcpLine(g_line, g_lineLen);
  g_lineLen = 0;
}

static void feedTcpByte(char c) {
  if (c == '\n') {
    flushTcpLine();
    return;
  }
  if (g_lineLen >= LINE_BUF_SIZE - 1) {
    // 超长行：原样冲给 STM32，避免缓冲区溢出丢数据
    Serial.write((const uint8_t*)g_line, g_lineLen);
    g_lineLen = 0;
  }
  g_line[g_lineLen++] = c;

  // 兼容【不发换行符】的调试工具（串口助手默认常常不带换行）。
  // 协议保证每一帧都以 "*00" 结尾，所以见到它就认为整帧到齐了。
  if (g_lineLen >= 3 &&
      g_line[g_lineLen - 3] == '*' &&
      g_line[g_lineLen - 2] == '0' &&
      g_line[g_lineLen - 1] == '0') {
    flushTcpLine();
  }
}

// ============================== setup / loop ==============================

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println();                      // 让串口监视器干净地开始
  debug("=== health_mon_esp8266 v2 booting ===");

  WiFi.persistent(true);    // 让 SDK 把 WiFi 凭据持久化到它自己的扇区

  bool connected = false;

  // 两次尝试共享这同一份 20 秒预算 —— 到点就开自己的热点，不再无限等下去
  uint32_t bootStart = millis();
  uint32_t used, remain;

  // ---- 第一步：SDK 里保存过的凭据（App 上次配的）----
  used = millis() - bootStart;
  remain = (used >= STA_GIVEUP_MS) ? 0 : (STA_GIVEUP_MS - used);
  if (remain > 0) {
    uint32_t t = (remain < STA_SAVED_MS) ? remain : STA_SAVED_MS;
    Serial.print(F("#boot: trying SDK-saved WiFi (")); Serial.print(t); Serial.println(F("ms)"));
    if (connectSTA(NULL, NULL, t)) {
      connected = true;
      Serial.print(F("#STA ok (saved config), IP ")); Serial.println(WiFi.localIP());
    }
  }

#if USE_DEFAULT_WIFI
  // ---- 第二步：兜底默认热点（吃掉剩余预算）----
  if (!connected) {
    used = millis() - bootStart;
    remain = (used >= STA_GIVEUP_MS) ? 0 : (STA_GIVEUP_MS - used);
    if (remain > 0) {
      Serial.print(F("#boot: trying default WiFi [")); Serial.print(DEFAULT_SSID);
      Serial.print(F("] (")); Serial.print(remain); Serial.println(F("ms left)"));
      if (connectSTA(DEFAULT_SSID, DEFAULT_PASS, remain)) {
        connected = true;
        Serial.print(F("#STA ok (default), IP ")); Serial.println(WiFi.localIP());
      }
    }
  }
#endif

  if (connected) {
    g_mode = MODE_STA;
    String s = WiFi.SSID();
    credentialsRemember(s.c_str(), "");
  } else {
    // 20 秒内没连上：开自己的热点，并记下原因等 App 连上来时报给它
    snprintf(g_lastStaFail, sizeof(g_lastStaFail), "%s", statusReason(WiFi.status()));
    Serial.print(F("#gave up after ")); Serial.print(millis() - bootStart);
    Serial.print(F("ms, reason ")); Serial.println(g_lastStaFail);
    Serial.println(F("#opening own AP so you can still reach the device"));
    startAP();
  }

  startServer();

  debug("ready");
  Serial.print(F("#IP ")); Serial.println(localAddr());
  sendInfoFrame();
}

void loop() {
  // ---- 1. 接受新客户端（单客户端：新连接踢掉旧的）----
  if (g_server.hasClient()) {
    WiFiClient incoming = g_server.available();
    if (incoming) {
      if (g_clientWasUp) {
        debug("kick old client");
        g_client.stop();
        g_clientWasUp = false;
      }
      g_client = incoming;
      g_client.setNoDelay(true);         // 关 Nagle，小包低延迟
      g_clientWasUp = true;
      g_lineLen = 0;                     // 新连接：丢掉上一条连接的半截行
      debug("client connected");
      sendInfoFrame();                   // 通知两侧：有客户端了

      // 如果上次开机连 WiFi 失败了，趁现在把原因报给 App（只报一次）。
      // 这就是"保存+重启"方案下，用户拿到失败原因的途径。
      if (g_lastStaFail[0] != '\0') {
        char extra[48];
        snprintf(extra, sizeof(extra), ",WIFI=FAIL,RSN=%s", g_lastStaFail);
        sendInfoFrame(extra);
        g_lastStaFail[0] = '\0';
      }
    }
  }

  // ---- 2. TCP → 串口（按行，为了拦截 WiFi 命令）----
  //
  // 注意：绝对不能用 `else if (g_client)` 来判断断开 —— ESP8266 的
  // WiFiClient::operator bool() 返回的就是 connected()，断开时那个分支永远不成立，
  // 于是 STM32 永远收不到"客户端断开"的通知。必须用独立的 g_clientWasUp 标志。
  if (g_client.connected()) {
    while (g_client.available()) {
      feedTcpByte((char)g_client.read());
    }
  } else if (g_clientWasUp) {
    g_client.stop();
    g_clientWasUp = false;
    g_lineLen = 0;
    debug("client disconnected");
    sendInfoFrame();
  }

  // ---- 3. 串口 → TCP（STM32 发的数据帧，逐字节透传，延迟最低）----
  // 没有客户端时也要排空串口缓冲，否则缓冲区满了会丢数据
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (g_client.connected()) {
      g_client.write(c);
    }
  }

  // ---- 4. STA 掉线处理 ----
  if (g_mode == MODE_STA) {
    if (WiFi.status() != WL_CONNECTED) {
      if (g_staDownSince == 0) {
        g_staDownSince = millis();
        debug("STA lost");
      } else if (millis() - g_staDownSince > STA_LOST_MS) {
        debug("STA down too long -> switching to AP");
        startAP();
        restartServer();
        sendInfoFrame();
        g_staDownSince = 0;
      }
    } else {
      g_staDownSince = 0;
    }
  }

  // ---- 5. 喂看门狗 ----
  // 绝不能在这里用长 delay()：会丢串口数据并触发软看门狗复位
  yield();
}
