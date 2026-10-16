#!/usr/bin/env node
/*
 * fake-device.js —— 假设备：在电脑上模拟 STM32 + ESP8266
 *
 * 为什么要它：App 的网络层（NetManager）需要一个真实的 TCP 对端才能开发，
 * 而真硬件（STM32 主板）在同学手里、进度不可控。有了它，App 与硬件彻底解耦。
 *
 * 它产出的帧与固件、与 Simulator 三者同构 —— 用同一套字段和格式，
 * 所以"对着假设备调通了"就意味着"对着真设备也能通"（剩下的只有物理层差异）。
 *
 * 用法:  node tools/fake-device.js [port]        默认 8266
 *        node tools/fake-device.js 8266 --quiet  不打印每一帧
 *
 * 支持的命令（与设计文档 §3.5 一致）：
 *   $C,CMD=TH,BTHI=..,HRHI=..,WLOW=..   更新阈值，下一帧回显（用于验证隐式 ACK）
 *   $C,CMD=PUMP,MODE=n                  切换水泵模式
 *   $C,CMD=QUERY                        立刻补发一帧
 *   $C,CMD=CLRSTEPS                     步数清零
 *   $C,CMD=WIFI,...                     回一条 $I 状态帧（模拟 ESP 的拦截行为）
 *   $C,CMD=WIFISTATUS                   回状态帧
 *   $C,CMD=WIFIFORGET                   回状态帧
 */

const net = require("net");

const PORT = Number(process.argv[2]) || 8266;
const QUIET = process.argv.includes("--quiet");

// ---------------------------------------------------------------- 设备状态
const dev = {
  seq: 0,
  t0: Date.now(),
  btHi: 37.3,
  hrHi: 100,
  wLow: 20,
  pmode: 2,          // 0=手动关 1=手动开 2=自动
  pumpManual: 0,
  stepsBase: 0,
  stepsBaseAt: Date.now(),
  anomalies: 0,      // 位域，可用 --demo 触发
  systemOff: false,
};

let client = null;

// ---------------------------------------------------------------- 生成一帧
// 固定 1 位小数 —— 协议规定的小数格式。
//
// ⚠️ 这里必须用 Math.floor，不能用 s / 10：
// JavaScript 的 / 是浮点除法，366/10 得到 36.6，再拼 "." + 6 就成了 "36.6.6"。
// Java 里同样写法的 long/int 是整数除法（366/10 = 36），所以 Simulator.java 是对的、
// 而这里曾经是错的 —— 同一个表达式在两种语言里含义不同。
//
// 后果很隐蔽：Double.valueOf("36.6.6") 抛异常 -> 解析器保留哨兵 -> 界面显示 "--"，
// 不报错、只是悄悄没有数据。
function fmt1(v) {
  const s = Math.round(v * 10);
  return Math.floor(s / 10) + "." + Math.abs(s % 10);
}

function buildFrame() {
  const t = (Date.now() - dev.t0) / 1000;
  const seq = dev.seq;
  dev.seq = (dev.seq + 1) & 0xFFFF;

  if (dev.systemOff) {
    return `$D,SEQ=${seq},SW=0,AL=0,*00\n`;
  }

  const btAlarm = (dev.anomalies & 0x01) !== 0;
  const hrAlarm = (dev.anomalies & 0x02) !== 0;

  const bt = btAlarm ? 37.8 + 0.2 * Math.sin(t / 10) : 36.5 + 0.3 * Math.sin(t / 20);
  const hr = hrAlarm ? Math.round(115 + 5 * Math.sin(t / 5)) : Math.round(80 + 12 * Math.sin(t / 7));

  const steps = dev.stepsBase + Math.floor(((Date.now() - dev.stepsBaseAt) / 1000) * 1.8);

  // 水位三角波：下降 200s（80->15），补水 60s（15->80）
  const cycle = 260;
  const phase = t % cycle;
  const water = phase < 200
    ? 80 - (80 - 15) * (phase / 200)
    : 15 + (80 - 15) * ((phase - 200) / 60);
  const waterPct = Math.round(water);

  // 水泵：自动模式下低于阈值就开；手动模式下听指令
  const autoPump = waterPct < dev.wLow ? 1 : 0;
  const pump = dev.pmode === 2 ? autoPump : dev.pumpManual;

  let al = dev.anomalies;
  if (waterPct < dev.wLow) al |= 0x04;

  return `$D,SEQ=${seq},BT=${fmt1(bt)},HR=${hr},SPO2=--,STEPS=${steps},WATER=${waterPct}` +
         `,PUMP=${pump},PMODE=${dev.pmode},SW=1,AL=${al}` +
         `,BTHI=${fmt1(dev.btHi)},HRHI=${dev.hrHi},WLOW=${dev.wLow},*00\n`;
}

function infoFrame(mode, extra) {
  return `$I,IP=127.0.0.1,PORT=${PORT},MODE=${mode}${extra || ""},*00\n`;
}

// ---------------------------------------------------------------- 命令处理
function getField(line, key) {
  const m = line.match(new RegExp("[,^]" + key + "=([^,*]*)"));
  if (m) return m[1];
  const m2 = line.match(new RegExp("^\\$C,CMD=[^,]*," + key + "=([^,*]*)"));
  return m2 ? m2[1] : null;
}

function handleLine(line, sock) {
  const cmd = (line.match(/CMD=([A-Z]+)/) || [])[1];
  if (!cmd) return;

  const num = (k, d) => {
    const v = getField(line, k);
    const n = v === null ? NaN : Number(v);
    return Number.isFinite(n) ? n : d;
  };

  switch (cmd) {
    case "TH":
      dev.btHi = num("BTHI", dev.btHi);
      dev.hrHi = num("HRHI", dev.hrHi);
      dev.wLow = num("WLOW", dev.wLow);
      console.log(`  <- TH  BTHI=${dev.btHi} HRHI=${dev.hrHi} WLOW=${dev.wLow}  (下一帧回显)`);
      break;
    case "PUMP": {
      const m = num("MODE", -1);
      if (m === 0 || m === 1 || m === 2) {
        dev.pmode = m;
        dev.pumpManual = m === 1 ? 1 : 0;
        console.log(`  <- PUMP MODE=${m}  (下一帧 PMODE 回显)`);
      }
      break;
    }
    case "QUERY":
      sock.write(buildFrame());
      console.log("  <- QUERY  已补发一帧");
      break;
    case "CLRSTEPS":
      dev.stepsBase = 0;
      dev.stepsBaseAt = Date.now();
      console.log("  <- CLRSTEPS  步数已清零");
      break;
    case "WIFI":
      sock.write(infoFrame("AP", ",WIFI=SAVED"));
      console.log("  <- WIFI  (假设备只回 SAVED，不会真的切换网络)");
      break;
    case "WIFISTATUS":
      sock.write(infoFrame("AP"));
      break;
    case "WIFIFORGET":
      sock.write(infoFrame("AP", ",WIFI=FORGOTTEN"));
      console.log("  <- WIFIFORGET");
      break;
    default:
      console.log(`  <- 未知命令 ${cmd}（已忽略）`);
  }
}

// ---------------------------------------------------------------- 服务器
const server = net.createServer((sock) => {
  if (client) {
    console.log("已有客户端，踢掉旧的（与固件的单客户端策略一致）");
    client.destroy();
  }
  client = sock;
  sock.setNoDelay(true);
  console.log(`客户端已连接: ${sock.remoteAddress}:${sock.remotePort}`);

  // 连接后立刻发一条状态帧 —— 和真实固件的行为一致
  sock.write(infoFrame("AP"));

  let buf = "";
  sock.on("data", (d) => {
    buf += d.toString("utf8");
    let i;
    while ((i = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (line) handleLine(line, sock);
    }
    // 兼容不发换行的调试工具：协议保证每帧以 *00 结尾
    if (buf.endsWith("*00")) {
      handleLine(buf.trim(), sock);
      buf = "";
    }
    if (buf.length > 4096) buf = buf.slice(-512);
  });

  sock.on("close", () => {
    console.log("客户端已断开");
    if (client === sock) client = null;
  });
  sock.on("error", (e) => console.log("socket 错误: " + e.message));
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`假设备已启动，监听 0.0.0.0:${PORT}`);
  console.log("  App 里填【本机的局域网 IP】和这个端口");
  console.log("  用 ipconfig 查本机 IP（手机与电脑要在同一网络）");
  console.log("  演示报警：改 tools/fake-device.js 里 anomalies 初值为 1 或 2");
  console.log("");
});

setInterval(() => {
  if (client && !client.destroyed) {
    const f = buildFrame();
    client.write(f);
    if (!QUIET) process.stdout.write("\r  -> " + f.trim().slice(0, 96).padEnd(96));
  }
}, 1000);

process.on("SIGINT", () => {
  console.log("\n假设备已停止");
  server.close();
  process.exit(0);
});
