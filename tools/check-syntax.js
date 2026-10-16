#!/usr/bin/env node
/*
 * check-syntax.js —— 第 3 层验证：没有 Android SDK 也能做的编译检查
 *
 * 思路：不用 SDK 直接编译整个项目，javac 必然报错 —— 但错误【不是等价的】：
 *
 *   - 语法错（expected / illegal start / unclosed ...）      -> 云端一定会挂，现在就修
 *   - cannot find symbol 指向【本项目自己的类】               -> 真错误，现在就修
 *   - package android.xxx does not exist                      -> 预期之中，忽略
 *
 * 分类完就能得到一句能干活的结论：要么"可以推上去让 CI 编"，要么"这里就有真错"。
 *
 * 为什么是 Node 而不是 PowerShell：本机沙箱是 ConstrainedLanguage，
 * PowerShell 里 [regex]::/List[string]/[guid]:: 这些 .NET 调用会被拦；
 * 而且旧实现把编译输出写进 %TEMP%，那个位置现在不允许写。Node 两个问题都没有。
 *
 * 用法:  node tools/check-syntax.js [--javac <path>] [--project <dir>]
 * 退出码: 0 = 只剩预期内的 android 缺失, 1 = 有真错, 2 = 环境不对
 */

const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

// ---------------------------------------------------------------- 参数
const argv = process.argv.slice(2);
function opt(name, def) {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : def;
}
const ROOT = path.resolve(opt("--project", path.resolve(__dirname, "..")));
const EXPLICIT_JAVAC = opt("--javac", null);

const SRC_ROOT = path.join(ROOT, "app/src/main/java");
if (!fs.existsSync(SRC_ROOT)) {
  console.error(`FATAL: ${SRC_ROOT} does not exist`);
  process.exit(2);
}

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

  for (const guess of ["D:/STM32 CubeMx/jre", "C:/Program Files/Java", "C:/Program Files/Eclipse Adoptium"]) {
    if (!fs.existsSync(guess)) continue;
    tried.push(`known location: ${guess}`);
    const direct = bin(guess);
    if (fs.existsSync(direct)) return { path: direct, tried };
    for (const sub of fs.readdirSync(guess)) {
      const p = bin(guess, sub);
      if (fs.existsSync(p)) return { path: p, tried };
    }
  }

  const which = spawnSync(process.platform === "win32" ? "where" : "which", [exe], { encoding: "utf8" });
  if (which.status === 0) {
    const p = which.stdout.split(/\r?\n/)[0].trim();
    if (p) return { path: p, tried: tried.concat(["PATH: " + p]) };
  }
  tried.push("PATH: (no javac)");
  return { path: null, tried };
}

// ---------------------------------------------------------------- 收集源文件
function walk(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else if (e.name.endsWith(".java")) out.push(p);
  }
  return out;
}

const sources = walk(SRC_ROOT).sort();
if (sources.length === 0) {
  console.error(`FATAL: found 0 .java files under ${SRC_ROOT} -- nothing was checked.`);
  process.exit(2);
}

// ---------------------------------------------------------------- 项目自己的类型
//
// 用来区分"找不到的符号是我自己写错的"还是"那是 SDK 的类"。
const ownTypes = new Set();
for (const f of sources) {
  const text = fs.readFileSync(f, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
  for (const m of text.matchAll(/\b(?:class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)/g)) {
    ownTypes.add(m[1]);
  }
}
if (ownTypes.size === 0) {
  console.error("FATAL: found 0 declared types -- nothing was checked.");
  process.exit(2);
}

// ---------------------------------------------------------------- 编译
const { path: javac, tried } = findJavac(EXPLICIT_JAVAC);
if (!javac) {
  console.error("FATAL: javac not found. Tried:");
  for (const t of tried) console.error("  " + t);
  console.error("");
  console.error("Fix: pass --javac <path>, or set JDK_HOME.");
  process.exit(2);
}

const OUT_DIR = path.join(ROOT, "tools/out/syntax");
fs.rmSync(OUT_DIR, { recursive: true, force: true });
fs.mkdirSync(OUT_DIR, { recursive: true });
const LOG = path.join(ROOT, "tools/out/syntax.log");

// ---- Android API 桩 ----
//
// 没有桩的时候，javac 解析不了 android.* 就不再检查用到它们的方法体 ——
// 实测往 Db.java 注入"返回值类型不匹配 / 方法名拼错 / 引用不存在的字段"
// 一个都抓不到（1/4）。加上桩以后这些都能在本机一秒内发现，
// 而不是等 8 分钟一次的云构建。
//
// 桩是近似：桩与 CI 冲突时以 CI 为准，并立刻修桩。
const STUB_DIR = path.join(ROOT, "tools/android-stubs");
const stubSources = fs.existsSync(STUB_DIR) ? walk(STUB_DIR).sort() : [];

// ---- 生成 R 类 ----
//
// R 是 AAPT 从资源生成的，没有 SDK 就没有它，于是所有 R.xxx 引用都会变成
// "cannot find symbol"，把真错淹没在噪声里。
//
// 手写一份 R 会随资源变化而过期（而且过期后是假绿灯：新加的资源没进去，
// 引用它的代码反而报错，人就会去关掉这个检查）。所以按 res/ 现场生成。
function collectRes() {
  const kinds = ["layout", "id", "string", "color", "drawable", "menu", "dimen", "style"];
  const out = {};
  for (const k of kinds) out[k] = new Set();

  const RES = path.join(ROOT, "app/src/main/res");
  if (!fs.existsSync(RES)) return out;

  for (const entry of fs.readdirSync(RES, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const dirName = entry.name;
    const dir = path.join(RES, dirName);
    if (dirName === "values" || dirName.startsWith("values-")) {
      for (const f of fs.readdirSync(dir)) {
        if (!f.endsWith(".xml")) continue;
        const t = fs.readFileSync(path.join(dir, f), "utf8");
        for (const m of t.matchAll(/<(string|color|dimen|style|integer|bool)\s+name="([A-Za-z0-9_]+)"/g)) {
          const kind = m[1] === "integer" || m[1] === "bool" ? null : m[1];
          if (kind && out[kind]) out[kind].add(m[2]);
        }
      }
      continue;
    }
    // 按目录名归类的资源（layout/、menu/、drawable/、drawable-hdpi/ ...）
    for (const base of ["layout", "menu", "drawable", "color"]) {
      if (dirName === base || dirName.startsWith(base + "-")) {
        if (!out[base]) continue;
        for (const f of fs.readdirSync(dir)) {
          if (f.endsWith(".xml") || base === "drawable") {
            out[base].add(f.replace(/\.xml$/, "").replace(/\.[a-z0-9]+$/i, ""));
          }
        }
      }
    }
  }

  // @+id/xxx 出现在任何 xml 里（layout 与 menu 都可能定义 id）
  for (const f of walk(RES)) {
    if (!f.endsWith(".xml")) continue;
    const t = fs.readFileSync(f, "utf8");
    for (const m of t.matchAll(/@\+id\/([A-Za-z0-9_]+)/g)) out.id.add(m[1]);
    // 引用形式的 @id/xxx 也算已定义（指向别处定义的 id）
    for (const m of t.matchAll(/@id\/([A-Za-z0-9_]+)/g)) out.id.add(m[1]);
  }
  return out;
}

const res = collectRes();
const GEN_DIR = path.join(ROOT, "tools/out/gen");
fs.mkdirSync(path.join(GEN_DIR, "com/example/healthmon"), { recursive: true });

let rSrc = "package com.example.healthmon;\n\n";
rSrc += "/** 由 tools/check-syntax.js 按 res/ 现场生成，不是源码。 */\n";
rSrc += "public final class R {\n  private R() {}\n";
let resTotal = 0;
let v = 0x7f010000;
for (const kind of ["layout", "id", "string", "color", "drawable", "menu", "dimen", "style"]) {
  const names = [...res[kind]].filter((n) => /^[A-Za-z_][A-Za-z0-9_]*$/.test(n)).sort();
  rSrc += `  public static final class ${kind} {\n    private ${kind}() {}\n`;
  for (const n of names) {
    rSrc += `    public static final int ${n} = 0x${(v++).toString(16)};\n`;
  }
  rSrc += "  }\n";
  resTotal += names.length;
}
rSrc += "}\n";

const R_FILE = path.join(GEN_DIR, "com/example/healthmon/R.java");
fs.writeFileSync(R_FILE, rSrc);

const compileList = stubSources.concat(sources, [R_FILE]);

// 子进程不能开命名管道，所以 stdout/stderr 重定向到文件再读回
const fd = fs.openSync(LOG, "w");
let status;
try {
  const r = spawnSync(
    javac,
    ["-J-Duser.language=en", "-J-Duser.country=US", "-encoding", "UTF-8", "-d", OUT_DIR].concat(compileList),
    { stdio: ["ignore", fd, fd] }
  );
  if (r.error) {
    console.error("FATAL: failed to launch javac:", r.error.message);
    process.exit(2);
  }
  status = r.status;
} finally {
  fs.closeSync(fd);
}
const raw = fs.readFileSync(LOG, "utf8");

if (status === 0) {
  console.log(`javac: ${javac}`);
  console.log(`sources: ${sources.length}`);
  console.log(`android stubs: ${stubSources.length}`);
  console.log(`generated R entries: ${resTotal}`);
  console.log("");
  if (stubSources.length > 0) {
    console.log("FULL LOCAL TYPE CHECK PASSED");
    console.log("整个项目对着 Android 桩编译通过了 —— 类型、方法签名、字段引用都是对的。");
    console.log("剩下的未知只有\"桩与真 API 是否一致\"，那要等 CI 回答。");
  } else {
    console.log("编译通过，但【没有启用桩】—— 请检查 tools/android-stubs 是否存在，");
    console.log("否则方法体根本没被检查过（这是假绿灯的一种）。");
    process.exit(2);
  }
  process.exit(0);
}

// ---------------------------------------------------------------- 分类
//
// 【为什么是黑名单而不是白名单】
// 早先的版本只把匹配若干固定模式的错误算作"真错"（expected / illegal start ...）。
// 那套写法是为"完全没有 SDK"的场景设计的 —— 那时 javac 只会报
// "package does not exist" 和 "cannot find symbol" 两种。
//
// 加上 Android 桩之后，javac 开始报 incompatible types、no suitable method found、
// method does not override 等等【别的种类】。白名单一条都不认，于是这些真错
// 全被当成"预期内的 android 缺失"放行了 —— 检查器看不懂的错被当成了没有错。
// 这是最危险的一种假绿灯。
//
// 所以反过来：只有能明确归因到"缺少 SDK"的错误才放行，其余一律算真错。
const lines = raw.split(/\r?\n/);

// javac 的每条 error 后面可能跟 symbol:/location: 等续行
const blocks = [];
let cur = null;
for (const l of lines) {
  if (/error:/.test(l)) {
    if (cur) blocks.push(cur);
    cur = [l];
  } else if (cur) {
    cur.push(l);
  }
}
if (cur) blocks.push(cur);

// 明确可以放行的：某个 SDK 包整体不存在（说明这条错误只是缺 SDK，不是代码写错）
const SDK_PACKAGE_MISSING = /package\s+[\w.]+\s+does not exist/;

// 语法类错误单独统计，因为它们的修复方式和类型错误不同
const SYNTAX_PATTERNS = [
  /expected/i, /illegal start/i, /unclosed/i, /reached end of file/i,
  /not a statement/i, /<identifier>/i, /class, interface, enum, or record expected/i,
];

const syntaxErrors = [];
const realErrors = [];
const missingPackages = new Set();
let expectedSdkErrors = 0;

for (const b of blocks) {
  const text = b.join("\n");
  const firstLine = (text.match(/error:\s*(.*)$/m) || [, ""])[1].trim();

  for (const pm of text.matchAll(/package\s+([\w.]+)\s+does not exist/g)) {
    missingPackages.add(pm[1]);
  }

  if (SDK_PACKAGE_MISSING.test(text)) {
    expectedSdkErrors++;
    continue;                        // 缺 SDK，放行
  }

  const where = (text.match(/^([^\s:]+\.java:\d+)/m) || [, "?"])[1];
  if (SYNTAX_PATTERNS.some((re) => re.test(firstLine))) {
    syntaxErrors.push(`${where}: ${firstLine}`);
  } else {
    // 还有 symbol: 行的，把符号名也带上，便于判断是桩缺了还是代码错了
    const sym = (text.match(/symbol:\s+[^\n]*/) || [])[0];
    realErrors.push(`${where}: ${firstLine}${sym ? "   [" + sym.trim() + "]" : ""}`);
  }
}

const symTotal = lines.filter((l) => /cannot find symbol/.test(l)).length;
const androidMissing = [...missingPackages].filter((p) => p.startsWith("android"));

console.log(`javac: ${javac}`);
console.log(`sources: ${sources.length}`);
console.log(`android stubs: ${stubSources.length}${stubSources.length ? "  (启用了真实类型检查)" : "  (无桩：方法体不会被检查)"}`);
console.log(`project-declared types: ${ownTypes.size}`);
console.log("");
console.log("error classification:");
console.log(`  syntax-class errors          : ${syntaxErrors.length}`);
console.log(`  other real errors            : ${realErrors.length}   <- 类型不匹配/方法不存在等`);
console.log(`  expected (missing SDK pkgs)  : ${expectedSdkErrors}   <- 有桩时应为 0`);
console.log(`  missing packages seen        : ${[...missingPackages].join(", ") || "(none)"}`);

const real = syntaxErrors.concat(realErrors);
if (real.length) {
  console.log("");
  console.log("REAL errors found (the cloud build will fail):");
  for (const e of new Set(real)) console.log("  " + e);
  console.log("");
  console.log("提示：若是 cannot find symbol 且指向 android.*，多半是桩缺了这个签名 —— 补桩，别改代码。");
  process.exit(1);
}

console.log("");
console.log("No real errors: every remaining failure is attributable to the missing SDK.");
console.log("Safe to push and let CI compile it.");
process.exit(0);
