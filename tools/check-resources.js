#!/usr/bin/env node
/**
 * Android 资源交叉检查 —— 不需要 Android SDK 或编译器。
 *
 * 它回答一个问题：代码里引用的每个 R.id / R.layout / R.string / R.color /
 * R.drawable，在资源文件里真的定义了吗？
 *
 * 这是 Android 构建失败最常见的原因，而且本地没有 android.jar 时编译器帮不了你。
 *
 * 用法:  node check-resources.js <项目根目录>
 * 退出码: 0 = 全部有定义且无重复 id; 1 = 发现问题
 */
const fs = require("fs");
const path = require("path");

const root = process.argv[2];
if (!root) {
  console.error("usage: node check-resources.js <projectRoot>");
  process.exit(2);
}

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

function byExt(files, ext) {
  return files.filter((f) => f.endsWith(ext));
}

const javaRoot = path.join(root, "app/src/main/java");
const resRoot = path.join(root, "app/src/main/res");

// Fail loudly on a wrong root.
//
// Without these guards the tool prints "RESOURCE CHECK OK" for a directory that contains
// nothing at all: walk() returns [], there is nothing to check, and zero problems looks
// identical to a clean project. That is the false-green anti-pattern -- a checker that never
// opened a file must not be able to pass.
if (!fs.existsSync(root)) {
  console.error(`FATAL: project root does not exist: ${root}`);
  process.exit(2);
}
if (!fs.existsSync(javaRoot) || !fs.existsSync(resRoot)) {
  console.error("FATAL: this does not look like an Android Gradle app module.");
  console.error(`  expected: ${javaRoot}`);
  console.error(`  expected: ${resRoot}`);
  console.error("  Assumes the standard app/src/main/{java,res} layout.");
  console.error("  For another layout (multi-module, renamed module, Compose-only), pass the");
  console.error("  module root instead, or edit the two paths above.");
  process.exit(2);
}

const javaFiles = byExt(walk(javaRoot), ".java");
const resFiles = walk(resRoot);

if (javaFiles.length === 0) {
  console.error(`FATAL: found 0 .java files under ${javaRoot} -- nothing was checked.`);
  process.exit(2);
}
if (resFiles.length === 0) {
  console.error(`FATAL: found 0 files under ${resRoot} -- nothing was checked.`);
  process.exit(2);
}

const layoutFiles = resFiles.filter((f) => f.includes(`${path.sep}layout${path.sep}`) && f.endsWith(".xml"));
const valueFiles = resFiles.filter((f) => f.includes(`${path.sep}values`) && f.endsWith(".xml"));
const menuFiles = resFiles.filter((f) => f.includes(`${path.sep}menu${path.sep}`) && f.endsWith(".xml"));

// ---- 收集已定义的资源 ----
const definedIds = new Map(); // id -> [files]  （用于查重复定义）
function addId(id, file) {
  if (!definedIds.has(id)) definedIds.set(id, []);
  definedIds.get(id).push(path.basename(file));
}
for (const f of [...layoutFiles, ...menuFiles]) {
  const s = fs.readFileSync(f, "utf8");
  // 只统计"定义"（@+id/），引用（@id/）不算
  for (const m of s.matchAll(/android:id="@\+id\/([A-Za-z0-9_]+)"/g)) addId(m[1], f);
}

const definedStrings = new Set();
const definedColors = new Set();
const definedDrawables = new Set();
for (const f of valueFiles) {
  const s = fs.readFileSync(f, "utf8");
  for (const m of s.matchAll(/<string\s+name="([A-Za-z0-9_]+)"/g)) definedStrings.add(m[1]);
  for (const m of s.matchAll(/<color\s+name="([A-Za-z0-9_]+)"/g)) definedColors.add(m[1]);
}
for (const f of resFiles) {
  if (f.includes(`${path.sep}drawable`)) {
    definedDrawables.add(path.basename(f).replace(/\.[a-z]+$/, ""));
  }
}

const layoutNames = new Set(layoutFiles.map((p) => path.basename(p, ".xml")));

// ---- 检查 Java 里的引用 ----
const problems = [];
const used = { id: new Set(), string: new Set(), layout: new Set(), color: new Set(), drawable: new Set() };

// 负向后顾排除 android.R.*（框架自带资源，不是项目资源）
const REF = [
  ["id", /(?<!android\.)\bR\.id\.([A-Za-z0-9_]+)/g, definedIds, (n) => `R.id.${n} 未在任何 layout/menu 中定义`],
  ["layout", /(?<!android\.)\bR\.layout\.([A-Za-z0-9_]+)/g, layoutNames, (n) => `R.layout.${n} 不存在`],
  ["string", /(?<!android\.)\bR\.string\.([A-Za-z0-9_]+)/g, definedStrings, (n) => `R.string.${n} 未定义`],
  ["color", /(?<!android\.)\bR\.color\.([A-Za-z0-9_]+)/g, definedColors, (n) => `R.color.${n} 未定义`],
  ["drawable", /(?<!android\.)\bR\.drawable\.([A-Za-z0-9_]+)/g, definedDrawables, (n) => `R.drawable.${n} 未定义`],
];

for (const f of javaFiles) {
  const base = path.basename(f);
  const s = fs.readFileSync(f, "utf8");
  for (const [kind, re, defined, msg] of REF) {
    for (const m of s.matchAll(re)) {
      used[kind].add(m[1]);
      if (!defined.has(m[1])) problems.push(`${base}: ${msg(m[1])}`);
    }
  }
}

// ---- 检查布局/xml 里对资源的引用 ----
for (const f of [...layoutFiles, ...menuFiles]) {
  const base = path.basename(f);
  const s = fs.readFileSync(f, "utf8");
  for (const m of s.matchAll(/@string\/([A-Za-z0-9_]+)/g)) {
    if (!definedStrings.has(m[1])) problems.push(`${base}: @string/${m[1]} 未定义`);
  }
  for (const m of s.matchAll(/@layout\/([A-Za-z0-9_]+)/g)) {
    if (!layoutNames.has(m[1])) problems.push(`${base}: @layout/${m[1]} 不存在`);
  }
  for (const m of s.matchAll(/@color\/([A-Za-z0-9_]+)/g)) {
    if (!definedColors.has(m[1])) problems.push(`${base}: @color/${m[1]} 未定义`);
  }
  for (const m of s.matchAll(/@drawable\/([A-Za-z0-9_]+)/g)) {
    if (!definedDrawables.has(m[1])) problems.push(`${base}: @drawable/${m[1]} 未定义`);
  }
}

// ---- 重复 id：会让 findViewById 命中错误的 View ----
for (const [id, files] of definedIds) {
  const uniq = [...new Set(files)];
  if (uniq.length > 1) problems.push(`id "${id}" 在多个文件中重复定义: ${uniq.join(", ")}`);
}

console.log(
  `layouts=${layoutNames.size} ids=${definedIds.size} strings=${definedStrings.size} ` +
    `javaFiles=${javaFiles.length}`
);
const unused = [...definedIds.keys()].filter((i) => !used.id.has(i));
console.log(`ids defined but unused from Java (${unused.length}): ${unused.join(", ") || "(none)"}`);

if (problems.length) {
  console.log(`\nPROBLEMS (${problems.length}):`);
  for (const p of problems) console.log("  FAIL " + p);
  process.exit(1);
}
console.log("\nRESOURCE CHECK OK");
