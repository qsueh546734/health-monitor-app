#!/usr/bin/env node
/*
 * capture-frames.js —— 起一个假设备，抓几帧真实输出，然后退出
 *
 * 用途：把"假设备实际吐出的字节"和"Java 解析器实际接受的格式"对齐。
 * 两边各自照文档实现，很容易在细节上错位（小数位数、字段名、
 * 无效值写法……），而这种错位只有把真实字节喂给真解析器才看得出来。
 *
 * 抓到的帧会被写进 tools/tests/FakeDeviceFormatTest.java 作为字面量。
 *
 * 用法:  node tools/capture-frames.js [port] [帧数]
 */

const net = require("net");
const { spawn } = require("child_process");
const path = require("path");

const PORT = Number(process.argv[2]) || 8299;
const WANT = Number(process.argv[3]) || 4;

// 子进程不能开命名管道（沙箱限制），所以 stdio 用 ignore 而不是默认的 pipe
const child = spawn(process.execPath, [path.join(__dirname, "fake-device.js"), String(PORT), "--quiet"], {
  stdio: "ignore",
});

const frames = [];
let done = false;

function finish(code) {
  if (done) return;
  done = true;
  try { child.kill(); } catch (e) { /* ignore */ }
  process.exit(code);
}

setTimeout(() => {
  const sock = net.connect(PORT, "127.0.0.1", () => {
    // 顺便验证命令回显路径：TH 改阈值、PUMP 改模式
    sock.write("$C,CMD=TH,BTHI=37.5,HRHI=105,WLOW=25,*00\n");
    setTimeout(() => sock.write("$C,CMD=PUMP,MODE=1,*00\n"), 900);
  });

  let buf = "";
  sock.on("data", (d) => {
    buf += d.toString("utf8");
    let i;
    while ((i = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (line) frames.push(line);
    }
    const dataFrames = frames.filter((f) => f.startsWith("$D"));
    if (dataFrames.length >= WANT + 1 && !done) {
      sock.destroy();
      const picked = frames.filter((f) => f.startsWith("$I") || f.startsWith("$D")).slice(0, WANT + 1);
      console.log("捕获 " + picked.length + " 行：");
      picked.forEach((f) => console.log(f));
      const bad = picked.filter((f) => f.startsWith("$D")).filter((f) => /=\d+\.\d+\./.test(f));
      if (bad.length) {
        console.log("\n!! 发现畸形数字（形如 36.6.6）:");
        bad.forEach((f) => console.log("   " + f));
        finish(1);
      }
      finish(0);
    }
  });

  sock.on("error", (e) => { console.error("连接失败: " + e.message); finish(2); });
}, 700);

setTimeout(() => { console.error("超时：假设备没有按时发帧"); finish(2); }, 12000);
