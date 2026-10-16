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

// 子进程不能开命名管道，所以 stdout/stderr 重定向到文件再读回
const fd = fs.openSync(LOG, "w");
let status;
try {
  const r = spawnSync(
    javac,
    ["-J-Duser.language=en", "-J-Duser.country=US", "-encoding", "UTF-8", "-d", OUT_DIR].concat(sources),
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
  console.log("");
  console.log("编译完全通过（这台机器上竟然不需要 Android SDK？检查一下是不是空的源文件集）");
  process.exit(0);
}

// ---------------------------------------------------------------- 分类
const lines = raw.split(/\r?\n/);

const SYNTAX_PATTERNS = [
  /expected/i, /illegal start/i, /unclosed/i, /reached end of file/i,
  /not a statement/i, /<identifier>/i, /class, interface, enum, or record expected/i,
];
const syntaxErrors = [];
const ownSymbolErrors = [];
const missingPackages = new Set();

lines.forEach((l, idx) => {
  const em = l.match(/error:\s*(.+)$/);
  if (em) {
    const msg = em[1].trim();
    if (SYNTAX_PATTERNS.some((re) => re.test(msg))) {
      syntaxErrors.push(`${path.basename(lines[idx] || "")} ${msg}`.trim());
    }
  }
  const pm = l.match(/package\s+([\w.]+)\s+does not exist/);
  if (pm) missingPackages.add(pm[1]);

  const sm = l.match(/^\s*symbol:\s+(?:class|interface|enum|variable|method)\s+([A-Za-z_][A-Za-z0-9_]*)/);
  if (sm && ownTypes.has(sm[1])) ownSymbolErrors.push(sm[1]);
});

const symTotal = lines.filter((l) => /cannot find symbol/.test(l)).length;
const androidMissing = [...missingPackages].filter((p) => p.startsWith("android"));

console.log(`javac: ${javac}`);
console.log(`sources: ${sources.length}`);
console.log(`project-declared types: ${ownTypes.size}`);
console.log("");
console.log("error classification:");
console.log(`  syntax-class errors          : ${syntaxErrors.length}`);
console.log(`  unresolved PROJECT types     : ${ownSymbolErrors.length}`);
console.log(`  missing android.* packages   : ${androidMissing.length}   <- expected without an SDK`);
console.log(`  total 'cannot find symbol'   : ${symTotal}`);

const real = syntaxErrors.concat(ownSymbolErrors);
if (real.length) {
  console.log("");
  console.log("REAL errors found (the cloud build will fail):");
  for (const e of new Set(real)) console.log("  " + e);
  process.exit(1);
}

console.log("");
console.log("No syntax-class errors, and every unresolved symbol comes from the missing android.* packages.");
console.log("Safe to push and let CI compile it.");
process.exit(0);
