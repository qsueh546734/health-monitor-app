#!/usr/bin/env node
/*
 * run-tests.js —— 纯 Java 逻辑测试（不需要 Android SDK）
 *
 * 为什么是 Node 而不是 PowerShell：本机沙箱跑在 ConstrainedLanguage 模式下，
 * PowerShell 里 New-Object / .Add() / [System.IO.File]::* 这类 .NET 调用会被拦。
 * Node 不受这个限制，而且参数用数组传给子进程，不会被 shell 重新分词 ——
 * 之前 javac 的 -J-Duser.language=en 被 PowerShell 拆坏、导致 grep 匹配不到任何东西
 * 从而"看起来通过"的假绿灯，在这个实现里不可能再发生。
 *
 * 用法:  node tools/run-tests.js [--javac <path>]
 * 退出码: 0 = 全部通过, 1 = 有失败, 2 = 环境不对（找不到 javac / 源文件）
 */

const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const SRC_DIR = path.join(ROOT, "app/src/main/java/com/example/healthmon");
const TEST_DIR = path.join(ROOT, "tools/tests");
const OUT_DIR = path.join(ROOT, "tools/out");

// ---------------------------------------------------------------- javac 定位
function findJavac(explicit) {
  const exe = process.platform === "win32" ? "javac.exe" : "javac";
  const bin = (...p) => path.join(...p, "bin", exe);

  const tried = [];
  const check = (p, label) => {
    if (!p) return null;
    tried.push(`${label}: ${p}`);
    return fs.existsSync(p) ? p : null;
  };

  let hit = check(explicit, "--javac");
  if (hit) return { path: hit, tried };

  hit = check(process.env.JDK_HOME ? bin(process.env.JDK_HOME) : null, "JDK_HOME");
  if (hit) return { path: hit, tried };

  hit = check(process.env.JAVA_HOME ? bin(process.env.JAVA_HOME) : null, "JAVA_HOME");
  if (hit) return { path: hit, tried };

  // 机器上已有的 JDK：STM32CubeMX 自带一个完整的 Temurin JDK（带 javac）
  for (const guess of [
    "D:/STM32 CubeMx/jre",
    "C:/Program Files/Java",
    "C:/Program Files/Eclipse Adoptium",
  ]) {
    if (!fs.existsSync(guess)) continue;
    tried.push(`known location: ${guess}`);
    // 可能本身就是 JDK 根，也可能下面还有一层版本目录
    const direct = bin(guess);
    if (fs.existsSync(direct)) return { path: direct, tried };
    for (const sub of fs.readdirSync(guess)) {
      const p = bin(guess, sub);
      if (fs.existsSync(p)) return { path: p, tried };
    }
  }

  // 最后交给 PATH
  const which = spawnSync(process.platform === "win32" ? "where" : "which", [exe], {
    encoding: "utf8",
  });
  if (which.status === 0) {
    const p = which.stdout.split(/\r?\n/)[0].trim();
    if (p) return { path: p, tried: tried.concat(["PATH: " + p]) };
  }
  tried.push("PATH: (no javac)");
  return { path: null, tried };
}

// ---------------------------------------------------------------- 选纯 Java 类
//
// 不维护硬编码清单：任何【不 import android.*】的类都自动纳入测试范围。
// 这条规则就是"纯 Java 核心"这个架构约束本身，写在代码里而不是文档里。
//
// ⚠️ 必须先剥掉【块注释】再判断。
// 只剥行注释是不够的：NetManager 的类文档里写了"刻意不依赖 android.os.Handler"，
// 于是它被当成依赖 android 的类排除掉了 —— 而它其实是纯 Java 的。
// 同一个陷阱（检查器把自己的文档当成代码）在本项目已经出现三次：
//   1. check-firmware.js 把"绝对不能用 else if (g_client)"这句注释报成 bug
//   2. 同一处的第二例
//   3. 这里
// 规则：任何做模式匹配的检查器，匹配前必须剥注释。
function isPureJava(file) {
  const text = fs
    .readFileSync(file, "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, "")   // 块注释
    .replace(/\/\/[^\n]*/g, "");        // 行注释
  return !/^\s*import\s+android\./m.test(text) && !/\bandroid\s*\./.test(text);
}

function listPureSources() {
  if (!fs.existsSync(SRC_DIR)) return [];
  return fs
    .readdirSync(SRC_DIR)
    .filter((f) => f.endsWith(".java"))
    .map((f) => path.join(SRC_DIR, f))
    .filter(isPureJava);
}

// ---------------------------------------------------------------- 输出重定向
//
// 沙箱里子进程【不能开命名管道】，所以 spawn 的默认 stdio:'pipe' 会 EPERM。
// 改成把 stdout/stderr 重定向到一个文件（工作区内，可写），再读回来。
function run(cmd, args, logFile) {
  const fd = fs.openSync(logFile, "w");
  try {
    const r = spawnSync(cmd, args, { stdio: ["ignore", fd, fd] });
    return { status: r.status, error: r.error };
  } finally {
    fs.closeSync(fd);
  }
}

// ---------------------------------------------------------------- 主流程
function main() {
  const argv = process.argv.slice(2);
  const i = argv.indexOf("--javac");
  const explicit = i >= 0 ? argv[i + 1] : null;

  const { path: javac, tried } = findJavac(explicit);
  if (!javac) {
    console.error("javac not found. Tried:");
    for (const t of tried) console.error("  " + t);
    console.error("");
    console.error("Fix: pass --javac <path>, or set JDK_HOME.");
    process.exit(2);
  }
  const java = path.join(path.dirname(javac), process.platform === "win32" ? "java.exe" : "java");

  const pure = listPureSources();
  if (pure.length === 0) {
    console.error(`FATAL: no pure-Java sources under ${SRC_DIR}`);
    console.error("       (a class counts as pure when it does not import android.*)");
    process.exit(2);
  }

  if (!fs.existsSync(TEST_DIR)) {
    console.error(`FATAL: no test directory ${TEST_DIR}`);
    process.exit(2);
  }
  const tests = fs
    .readdirSync(TEST_DIR)
    .filter((f) => f.endsWith("Test.java"))
    .map((f) => f.replace(/\.java$/, ""));
  if (tests.length === 0) {
    console.error(`FATAL: no *Test.java under ${TEST_DIR}`);
    process.exit(2);
  }

  fs.rmSync(OUT_DIR, { recursive: true, force: true });
  fs.mkdirSync(OUT_DIR, { recursive: true });

  console.log(`javac: ${javac}`);
  console.log(`pure sources: ${pure.length} (${pure.map((p) => path.basename(p)).join(", ")})`);
  console.log(`test classes: ${tests.length}`);

  // ---- 编译 ----
  const logCompile = path.join(OUT_DIR, "compile.log");
  const sources = pure.concat(fs.readdirSync(TEST_DIR).filter((f) => f.endsWith(".java")).map((f) => path.join(TEST_DIR, f)));

  // -J-Duser.language=en 强制英文错误信息，便于程序化判断；
  // 作为数组元素传递，不会被 shell 重新分词（历史教训）
  const compile = run(
    javac,
    ["-J-Duser.language=en", "-J-Duser.country=US", "-encoding", "UTF-8", "-d", OUT_DIR].concat(sources),
    logCompile
  );
  const compileOut = fs.readFileSync(logCompile, "utf8");
  if (compile.error) {
    console.error("FATAL: failed to launch javac:", compile.error.message);
    process.exit(2);
  }
  if (compile.status !== 0) {
    console.log("");
    console.log("COMPILE FAILED");
    console.log(compileOut.trim());
    process.exit(1);
  }
  if (compileOut.trim()) console.log(compileOut.trim());

  // ---- 运行每个测试类 ----
  let failed = 0;
  for (const t of tests) {
    const log = path.join(OUT_DIR, `${t}.log`);
    const r = run(java, ["-cp", OUT_DIR, t], log);
    const out = fs.readFileSync(log, "utf8").trim();
    const summary = out.split(/\r?\n/).filter((l) => /passed|failed/i.test(l)).pop() || "";
    if (out) console.log(out);
    if (r.status !== 0) {
      failed++;
      console.log(`  !! ${t} FAILED (exit ${r.status})`);
    } else {
      console.log(`  ${t.padEnd(20)} ${summary}`);
    }
  }

  console.log("");
  if (failed) {
    console.log(`${failed} of ${tests.length} test classes FAILED`);
    process.exit(1);
  }
  console.log(`ALL TESTS PASSED (${tests.length} classes)`);
  process.exit(0);
}

main();
