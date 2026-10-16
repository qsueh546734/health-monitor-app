#!/usr/bin/env node
/*
 * check-all.js —— 本地总闸门：一次跑完所有不需要设备、不需要 SDK 的检查
 *
 * 存在的理由：CI #4 和 #5 连续两次失败在同一个 XML 注释问题上，
 * 而本地其实"有"四个检查器 —— 只是没人为"XML 本身是否合法"负责。
 * 五道门分散成五条命令时，漏掉其中一条是迟早的事。
 *
 * 顺序是刻意的：从最便宜、最容易懂的错开始。
 *   1. XML        —— 资源文件本身是否合法（AAPT 第一个会挂的地方）
 *   2. 资源引用   —— R.xxx 引用的东西是否存在
 *   3. 类型检查   —— 对着 Android 桩编译整个项目
 *   4. 纯 Java 测试
 *   5. 固件       —— ESP8266 的协议与安全不变量
 *
 * 用法:  node tools/check-all.js
 * 退出码: 0 = 全过, 1 = 有门没过, 2 = 环境不对
 */

const path = require("path");
const { spawnSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..");

const GATES = [
  { name: "XML 合法性",       script: "tools/check-xml.js",        args: [ROOT] },
  { name: "资源引用",         script: "tools/check-resources.js",  args: [ROOT] },
  { name: "类型检查（桩）",   script: "tools/check-syntax.js",     args: ["--project", ROOT] },
  { name: "纯 Java 测试",     script: "tools/run-tests.js",        args: [] },
  { name: "固件检查",         script: "tools/check-firmware.js",   args: [] },
];

const results = [];

for (const g of GATES) {
  const scriptPath = path.join(ROOT, g.script);
  console.log("");
  console.log("=".repeat(60));
  console.log(`  ${g.name}   (${g.script})`);
  console.log("=".repeat(60));

  const r = spawnSync(process.execPath, [scriptPath].concat(g.args), {
    cwd: ROOT,
    stdio: "inherit",          // 沙箱里不能开命名管道，inherit 可以
  });
  const code = r.status === null ? 2 : r.status;
  results.push({ name: g.name, code });
  console.log(`  -> exit ${code}`);
}

console.log("");
console.log("=".repeat(60));
console.log("  总闸门结果");
console.log("=".repeat(60));
for (const r of results) {
  const mark = r.code === 0 ? "OK  " : "FAIL";
  console.log(`  ${mark}  ${r.name}  (exit ${r.code})`);
}

const failed = results.filter((r) => r.code !== 0);
if (failed.length) {
  console.log("");
  console.log(`${failed.length} 道门没过 —— 修完再推，别把本地能发现的错留给 CI。`);
  process.exit(1);
}
console.log("");
console.log("全部通过。可以推了。");
process.exit(0);
