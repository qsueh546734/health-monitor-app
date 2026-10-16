#!/usr/bin/env node
/*
 * check-xml.js —— 资源 XML 的合法性与结构检查
 *
 * 【为什么需要它】CI #4 和 #5 连续两次失败在同一个地方：
 *   activity_main.xml 的注释里写了分隔线 "<!-- ---------- 连接页 ---------- -->"，
 *   而 XML 规范【不允许注释内出现 "--"】。AAPT 直接报
 *   "The string \"--\" is not permitted within comments"。
 *
 * 这个错本机 1 秒就能查出来，我却让它白等了两轮 8 分钟的云构建 ——
 * 因为原有的本地检查（check-resources.js 查引用、check-syntax.js 查 Java）
 * 都【没有解析过 XML 本身】。
 *
 * 这是"本地验证要覆盖每一类错误"这条纪律的一个具体缺口。
 *
 * 用法:  node tools/check-xml.js [项目根目录]
 * 退出码: 0 = 全部合法, 1 = 有问题, 2 = 环境不对
 */

const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(process.argv[2] || path.resolve(__dirname, ".."));
const RES = path.join(ROOT, "app/src/main/res");
const MANIFEST = path.join(ROOT, "app/src/main/AndroidManifest.xml");

if (!fs.existsSync(RES)) {
  console.error(`FATAL: ${RES} does not exist`);
  process.exit(2);
}

function walk(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else if (e.name.endsWith(".xml")) out.push(p);
  }
  return out;
}

const files = walk(RES);
if (fs.existsSync(MANIFEST)) files.push(MANIFEST);
if (files.length === 0) {
  console.error(`FATAL: found 0 XML files under ${RES} -- nothing was checked.`);
  process.exit(2);
}

const problems = [];

/** 逐行定位（AAPT 也报行列号，保持一致便于对照） */
function at(file, index, msg) {
  const rel = path.relative(ROOT, file);
  problems.push(`${rel}:${index + 1}: ${msg}`);
}

for (const file of files) {
  const text = fs.readFileSync(file, "utf8");
  const lines = text.split(/\r?\n/);

  // ---- 1. 注释里不能有 "--"（本次 CI 失败的直接原因）----
  const commentRe = /<!--([\s\S]*?)-->/g;
  let m;
  while ((m = commentRe.exec(text)) !== null) {
    const body = m[1];
    if (body.indexOf("--") >= 0) {
      const line = text.slice(0, m.index).split(/\r?\n/).length - 1;
      at(file, line, `XML 注释里出现了 "--"（规范不允许，AAPT 会直接报错）`);
    }
  }

  // 未闭合的注释
  const openCount = (text.match(/<!--/g) || []).length;
  const closeCount = (text.match(/-->/g) || []).length;
  if (openCount !== closeCount) {
    at(file, 0, `注释没有配平：<!-- 出现 ${openCount} 次，--> 出现 ${closeCount} 次`);
  }

  // ---- 2. 标签配平 ----
  const noComment = text.replace(/<!--[\s\S]*?-->/g, "");
  const tagRe = /<(\/?)([A-Za-z_][\w.:-]*)([^>]*?)(\/?)>/g;
  const stack = [];
  let t;
  while ((t = tagRe.exec(noComment)) !== null) {
    const closing = t[1] === "/";
    const name = t[2];
    const selfClose = t[4] === "/";
    const attrs = t[3];
    const line = noComment.slice(0, t.index).split(/\r?\n/).length - 1;

    if (attrs.indexOf("<?") >= 0) continue;
    if (selfClose) continue;

    if (closing) {
      const top = stack.pop();
      if (!top) {
        at(file, line, `多余的闭合标签 </${name}>`);
      } else if (top.name !== name) {
        at(file, line, `闭合标签不匹配：期望 </${top.name}>（第 ${top.line + 1} 行开的），实际 </${name}>`);
      }
    } else {
      stack.push({ name, line });
    }
  }
  for (const left of stack) {
    at(file, left.line, `标签 <${left.name}> 没有闭合`);
  }

  // ---- 3. 属性值必须用引号 ----
  const attrRe = /\s([A-Za-z_][\w.:-]*)\s*=\s*([^\s"'][^\s>]*)/g;
  let a;
  while ((a = attrRe.exec(noComment)) !== null) {
    const line = noComment.slice(0, a.index).split(/\r?\n/).length - 1;
    at(file, line, `属性 ${a[1]} 的值没有加引号（AAPT 会报错）`);
  }

  // ---- 4. 裸露的 & 必须写成实体 ----
  const ampRe = /&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9A-Fa-f]+);)/g;
  let amp;
  while ((amp = ampRe.exec(noComment)) !== null) {
    const line = noComment.slice(0, amp.index).split(/\r?\n/).length - 1;
    at(file, line, `裸露的 & 必须写成 &amp;`);
  }

  // ---- 5. 首行必须是 XML 声明（AAPT 要求）----
  if (!/^\s*<\?xml\s/.test(lines[0] || "")) {
    at(file, 0, `缺少 <?xml version="1.0" encoding="utf-8"?> 声明`);
  }
}

console.log(`检查了 ${files.length} 个 XML 文件`);
if (problems.length) {
  console.log("");
  console.log("发现问题:");
  for (const p of problems) console.log("  FAIL " + p);
  process.exit(1);
}
console.log("");
console.log("XML CHECK OK");
