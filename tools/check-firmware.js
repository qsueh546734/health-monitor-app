#!/usr/bin/env node
/*
 * check-firmware.js —— ESP8266 固件静态自检
 *
 * 没有 ESP8266 工具链也能做的验证。分两类，各自能证明什么、不能证明什么要分清：
 *
 *   A. 结构检查（针对真实 C 代码）
 *      必备措施在不在、已知陷阱有没有复发、关键逻辑的边界判断有没有被改坏。
 *
 *   B. 算法测试（针对 logic 的 JS 复现）
 *      喂真实命令字符串验证算法行为。注意：它测的是"算法应该怎么写"，
 *      不能证明 C 代码里写的就是这个算法 —— 那一半由 A 类里对应的结构检查兜住。
 *      两类必须同时在，缺一个就会出现"改了 C 代码但测试还是绿的"。
 *
 * 用法:  node tools/check-firmware.js
 * 退出码: 0 = 通过, 1 = 发现问题
 */

const fs = require("fs");
const path = require("path");
const assert = require("assert");

const ROOT = path.resolve(__dirname, "..");
const INO = path.join(ROOT, "firmware/esp8266/health_mon_esp8266/health_mon_esp8266.ino");
const SPEC = path.join(
  ROOT,
  "docs/superpowers/specs/2026-10-15-长期健康监测App-设计文档.md"
);

const problems = [];
const notes = [];
let logicChecks = 0;

function fail(msg) {
  problems.push(msg);
}

// ---------------------------------------------------------------- 读文件
if (!fs.existsSync(INO)) {
  console.error(`FATAL: 找不到固件 ${INO}`);
  process.exit(2);
}
const src = fs.readFileSync(INO, "utf8");
const spec = fs.existsSync(SPEC) ? fs.readFileSync(SPEC, "utf8") : "";
if (!spec) notes.push("未找到设计文档，跳过帧格式比对");

// 剥掉注释再做代码模式匹配。
//
// 不做这一步会出假阳性：固件里"提醒不要这么写"的注释本身含有反面写法，
// 直接扫原文会把那条说明当成真的 bug 报出来。
const code = src
  .replace(/\/\*[\s\S]*?\*\//g, "")   // 块注释
  .replace(/\/\/[^\n]*/g, "");        // 行注释

// ================================================================
// A. 结构检查（针对真实 C 代码）
// ================================================================

// ---- A1. $I 帧的格式串（v2 起由 snprintf 拼装）----
const fmtMatch = code.match(/snprintf\(\s*buf\s*,\s*sizeof\(buf\)\s*,\s*"((?:[^"\\]|\\.)*)"/);
if (!fmtMatch) {
  fail("找不到 $I 帧的 snprintf 格式串");
} else {
  const f = fmtMatch[1];
  // 字段名与顺序必须都对 —— 拼错一个字母 App 就解析不出来
  const tokens = ["$I,IP=", ",PORT=%d", ",MODE=%s", ",*00\\n"];
  let pos = -1;
  for (const tk of tokens) {
    const idx = f.indexOf(tk);
    if (idx < 0) { fail(`$I 格式串缺少 ${JSON.stringify(tk)}（当前: ${f}）`); break; }
    if (idx < pos) { fail(`$I 格式串字段顺序错误: ${JSON.stringify(tk)}`); break; }
    pos = idx;
  }
  if (spec && !spec.includes("$I,IP=")) {
    fail("设计文档里找不到 $I 帧样例，两边无法比对");
  }
}

// ---- A2. 状态帧必须同时发给 STM32 和 App ----
const infoFn = code.match(/static void sendInfoFrame\(const char\* extra\)[\s\S]*?\n\}/);
if (!infoFn) {
  fail("找不到 sendInfoFrame(const char* extra)");
} else {
  if (!/Serial\.print\(\s*buf\s*\)/.test(infoFn[0])) {
    fail("sendInfoFrame 没有把状态帧发给 STM32（串口）");
  }
  if (!/g_client\.print\(\s*buf\s*\)/.test(infoFn[0])) {
    fail("sendInfoFrame 没有把状态帧发给 App（TCP）—— App 将收不到配置结果");
  }
}

// ---- A3. cmdIs 的边界判断（B 类算法测试测不到这里）----
const cmdIsFn = code.match(/static bool cmdIs\([\s\S]*?\n\}/);
if (!cmdIsFn) {
  fail("找不到 cmdIs");
} else {
  const b = cmdIsFn[0];
  if (!/char\s+after\s*=\s*p\s*\[\s*n\s*\]/.test(b)) {
    fail("cmdIs 没有取 p[n] 作为后继字符 —— 边界判断被改坏（WIFI 会误配 WIFIFORGET）");
  }
  if (!/after\s*==\s*','/.test(b)) fail("cmdIs 缺少 ',' 边界判断");
  if (!/after\s*==\s*'\*'/.test(b)) fail("cmdIs 缺少 '*' 边界判断");
  if (!/after\s*==\s*'\\0'/.test(b)) fail("cmdIs 缺少行尾 '\\0' 判断");
  // 比较方向也要检查：写成 == 0 的话，匹配成功的命令反而会被当成不匹配，
  // 整条配置链路直接失效（而前面几条边界检查仍然全部通过）
  if (!/strncmp\(\s*p\s*,\s*name\s*,\s*n\s*\)\s*!=\s*0/.test(b)) {
    fail("cmdIs 的前缀比较不是 '!=' 方向 —— 匹配成功的命令会被当成不匹配");
  }
}

// ---- A4. 已知陷阱回归 ----
const badPatterns = [
  [/else if \(g_client\)/, "残留 `else if (g_client)` —— 断开分支永远不会执行"],
  [/g_client\s*&&\s*g_client\.connected\(\)/, "多余的 `g_client && ...` —— operator bool 就是 connected()"],
];
for (const [re, why] of badPatterns) {
  if (re.test(code)) fail(why);
}
if (!code.includes("g_clientWasUp")) {
  fail("缺少 g_clientWasUp 标志 —— 客户端断开将无法被检测");
} else if (!/g_clientWasUp\s*=\s*true/.test(code)) {
  fail("g_clientWasUp 从未被置为 true —— 客户端连上后再断开仍然检测不到");
}

// ---- A5. 必备健壮性措施 ----
const required = [
  ["g_client.setNoDelay", "客户端未关闭 Nagle（小包会有延迟）"],
  ["WIFI_NONE_SLEEP", "未关闭 WiFi 省电（会导致随机掉线）"],
  ["yield()", "缺少 yield()，软看门狗可能复位"],
  ["STA_GIVEUP_MS", "缺少「连不上就开热点」的总预算 —— 现场热点出问题时会永远卡在连接中"],
  ["STA_LOST_MS", "缺少运行中掉线后开热点的逻辑"],
  ["startAP", "缺少热点模式 —— 连不上时用户将无法访问设备"],
  ["softAP", "缺少 softAP 调用"],
];

// 总预算必须是个合理值：0 会让它永远开热点，过大则等于没有兜底
{
  const m = code.match(/#define\s+STA_GIVEUP_MS\s+(\d+)UL/);
  if (!m) {
    fail("STA_GIVEUP_MS 未定义或格式不对（应为 #define STA_GIVEUP_MS <数字>UL）");
  } else {
    const ms = Number(m[1]);
    if (ms < 5000) fail(`STA_GIVEUP_MS=${ms}ms 太短，网络慢时会误判`);
    else if (ms > 120000) fail(`STA_GIVEUP_MS=${ms}ms 太长，等于没有兜底`);
  }
}

// 运行中掉线的兜底也不能无限等
{
  const m = code.match(/#define\s+STA_LOST_MS\s+(\d+)UL/);
  if (m) {
    const ms = Number(m[1]);
    if (ms > 120000) fail(`STA_LOST_MS=${ms}ms 太长，掉线后用户要干等很久`);
  }
}
for (const [needle, why] of required) {
  if (!code.includes(needle)) fail(why);
}

// ---- A6. WiFi 配置功能 ----
const wifiRequired = [
  // v4 起【不再使用 EEPROM】—— 实测它在这块 ESP-01S 上不落盘
  // （写进去后立刻回读是对的，但 ESP.restart() 之后读不回来），
  // 说明 Arduino EEPROM 的 flash 扇区被 SDK 的系统参数/RF 校准区覆盖了。
  // 改用 SDK 自带的 WiFi 持久化，那是专门为保存 WiFi 凭据设计的机制。
  ["WiFi.persistent", "没有启用 SDK 持久化 —— 凭据重启后会丢"],
  ["ESP.eraseConfig", "找不到清除 SDK 配置的调用（换网络时无法回到热点模式）"],
  ["credentialsRemember", "没有记录当前凭据的运行时副本"],
  ["credentialsForget", "没有清除运行时副本"],
  ["ESP.restart", "配置成功后没有重启，不会切到 STA 模式"],
  ["statusReason", "没有把 WiFi 状态翻译成原因码"],
  ["handleEspCommand", "没有命令拦截入口"],
  ["feedTcpByte", "TCP 接收没有按行缓冲，无法拦截命令"],
  // 调试工具（串口助手等）默认往往不发换行符，只认 '\n' 会导致命令石沉大海
  ["flushTcpLine", "没有独立的整行处理入口"],
  ["g_line[g_lineLen - 3] == '*'", "不兼容不发换行的工具：只认 '\\n' 会让命令永远不被处理"],
];
for (const [needle, why] of wifiRequired) {
  if (!code.includes(needle)) fail(why);
}

// 绝不能退回 EEPROM 方案（实测不落盘）
if (/EEPROM\.(begin|commit|read|write|put|get)/.test(code)) {
  fail("代码里又出现了 EEPROM 调用 —— 实测它在这块 ESP-01S 上不落盘，必须用 SDK 持久化");
}

// ---- A7. 安全性质 ----
const setFn = code.match(/static void handleWifiSet\([\s\S]*?\n\}\n/);
if (!setFn) {
  fail("找不到 handleWifiSet");
} else {
  const body = setFn[0];
  if (!/WiFi\.begin\(/.test(body)) fail("handleWifiSet 没有把凭据交给 SDK");
  if (!/ESP\.restart\(\)/.test(body)) fail("handleWifiSet 没有重启 —— 不会去连新网络");
  // 🔴 这是修掉过的真 bug：切 WiFi 模式（AP -> AP_STA/STA）会重配 softAP，
  //    已连上的 TCP 客户端立刻被踢掉，用户只看到"发出去就断线"。
  //    所以连接尝试只能在【开机阶段】做，命令处理里一次都不能有。
  if (/connectSTA\(/.test(body)) {
    fail("handleWifiSet 里调用了 connectSTA —— 切模式会踢掉当前 TCP 连接，用户只看到断线");
  }
}

// connectSTA 只能在 setup() 里被调用。
// 用"逐个客户端处理函数都不许出现 connectSTA"来表达，比数调用点更抗重构。
{
  const clientSideFns = [
    "handleWifiSet", "handleWifiForget", "handleWifiStatus", "handleEspCommand",
    "handleTcpLine", "feedTcpByte", "flushTcpLine", "loop",
  ];
  for (const name of clientSideFns) {
    const re = new RegExp(
      "(?:static\\s+[\\w\\s\\*]+\\s+)?" + name + "\\s*\\([^)]*\\)\\s*\\{[\\s\\S]*?\\n\\}\\n"
    );
    const m = code.match(re);
    if (!m) continue;                       // 名字变了就跳过，不误报
    if (/connectSTA\s*\(/.test(m[0])) {
      fail(`${name}() 里调用了 connectSTA —— 切 WiFi 模式会踢掉已连上的 TCP 客户端`);
    }
  }
}

const setupFn = code.match(/void setup\(\)[\s\S]*?\n\}/);
if (!setupFn) {
  fail("找不到 setup()");
} else if (!/connectSTA\(/.test(setupFn[0])) {
  fail("setup() 里没有连接 STA");
}

// 失败原因要能报回给 App
if (!/g_lastStaFail/.test(code)) {
  fail("没有记录 STA 失败原因 —— 用户配置错 WiFi 后收不到任何反馈");
}

// 兜底凭据必须是可关闭的编译期开关（否则"忘记网络"永远退不回热点模式）
if (!/#define\s+USE_DEFAULT_WIFI\s+[01]/.test(code)) {
  fail("没有 USE_DEFAULT_WIFI 编译期开关 —— 兜底凭据将无法关闭");
}

// ---- A8. 构造参数 ----
const pw = code.match(/#define\s+AP_PASS\s+"([^"]*)"/);
if (!pw) fail("找不到 AP_PASS 定义");
else if (pw[1].length < 8) fail(`AP_PASS 只有 ${pw[1].length} 位，softAP 要求至少 8 位`);

const port = code.match(/#define\s+TCP_PORT\s+(\d+)/);
if (!port) fail("找不到 TCP_PORT 定义");
else {
  const p = Number(port[1]);
  if (p < 1 || p > 65535) fail(`TCP_PORT ${p} 越界`);
  if (spec && !spec.includes(String(p))) fail(`TCP_PORT=${p} 在设计文档里找不到`);
}

// ================================================================
// B. 算法测试（JS 复现）
// ================================================================

// 复现 C 代码的 cmdIs(line, name)
function cmdIs(line, name) {
  const p = line.indexOf("CMD=");
  if (p < 0) return false;
  const at = p + 4;
  if (!line.startsWith(name, at)) return false;
  const after = line[at + name.length];
  return after === "," || after === "*" || after === undefined;
}

// 复现 C 代码的 getField(line, key, out, outSize)
function getField(line, key, outSize = Infinity) {
  const pat = key + "=";
  const p = line.indexOf(pat);
  if (p < 0) return null;
  if (p !== 0) {
    const prev = line[p - 1];
    if (prev !== "," && prev !== "$") return null;
  }
  let i = p + pat.length;
  let out = "";
  while (i < line.length && line[i] !== "," && line[i] !== "*" && out.length + 1 < outSize) {
    out += line[i++];
  }
  return out;
}

function t(desc, fn) {
  logicChecks++;
  try {
    fn();
  } catch (e) {
    fail(`${desc}: ${e.message}`);
  }
}

// 🔴 最关键的一组：前缀陷阱
t("cmdIs WIFI 不能匹配 WIFIFORGET", () => {
  assert.strictEqual(
    cmdIs("$C,CMD=WIFIFORGET,*00", "WIFI"),
    false,
    "把 WIFIFORGET 误判成了 WIFI —— 清空凭据会被当成配置网络！"
  );
});
t("cmdIs WIFI 不能匹配 WIFISTATUS", () => {
  assert.strictEqual(cmdIs("$C,CMD=WIFISTATUS,*00", "WIFI"), false);
});
t("cmdIs WIFI 精确匹配", () => {
  assert.strictEqual(cmdIs("$C,CMD=WIFI,SSID=a,PASS=b,*00", "WIFI"), true);
});
t("cmdIs WIFIFORGET 精确匹配", () => {
  assert.strictEqual(cmdIs("$C,CMD=WIFIFORGET,*00", "WIFIFORGET"), true);
});
t("cmdIs 行尾无分隔符也算匹配", () => {
  assert.strictEqual(cmdIs("$C,CMD=WIFIFORGET", "WIFIFORGET"), true);
});
t("cmdIs 不能匹配到别的命令里", () => {
  assert.strictEqual(cmdIs("$C,CMD=TH,BTHI=37.3,*00", "WIFI"), false);
});

// getField
t("getField 取 SSID", () => {
  assert.strictEqual(getField("$C,CMD=WIFI,SSID=MyNet,PASS=abc12345,*00", "SSID"), "MyNet");
});
t("getField 取 PASS", () => {
  assert.strictEqual(getField("$C,CMD=WIFI,SSID=MyNet,PASS=abc12345,*00", "PASS"), "abc12345");
});
t("getField 不能被 XSSID 蒙混", () => {
  assert.strictEqual(getField("$C,CMD=WIFI,XSSID=evil,*00", "SSID"), null);
});
t("getField 空密码允许（开放网络）", () => {
  assert.strictEqual(getField("$C,CMD=WIFI,SSID=Open,PASS=,*00", "PASS"), "");
});
t("getField 缺失返回 null", () => {
  assert.strictEqual(getField("$C,CMD=WIFI,SSID=OnlySsid,*00", "PASS"), null);
});
t("getField 密码含 '=' 仍正确", () => {
  assert.strictEqual(getField("$C,CMD=WIFI,SSID=A,PASS=a=b,*00", "PASS"), "a=b");
});
t("SSID 超长必须被 outSize 截断（不能溢出 EEPROM）", () => {
  const long = "S".repeat(60);
  const v = getField(`$C,CMD=WIFI,SSID=${long},PASS=x,*00`, "SSID", 33);
  assert.ok(v.length <= 32, `截断失败: 得到 ${v.length} 字节`);
  const ssidMax = Number((code.match(/#define\s+SSID_MAX\s+(\d+)/) || [])[1]);
  if (!(ssidMax > 0 && ssidMax <= 32)) {
    throw new Error(`SSID_MAX=${ssidMax} 不合理，EEPROM 只留了 33 字节`);
  }
  if (!/getField\(line, "SSID", ssid, sizeof\(ssid\)\)/.test(code)) {
    throw new Error("取 SSID 时没有传 sizeof(ssid)，可能溢出");
  }
});

// ================================================================
// 输出
// ================================================================
console.log(`固件: ${path.relative(ROOT, INO)}`);
console.log(`  算法测试     : ${logicChecks} 项 (JS 复现，配合 A3 结构检查)`);
for (const n of notes) console.log(`  注意: ${n}`);

if (problems.length) {
  console.log("");
  console.log("发现问题:");
  for (const p of problems) console.log(`  FAIL ${p}`);
  process.exit(1);
}

console.log("");
console.log("FIRMWARE CHECK OK");
