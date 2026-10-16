#!/usr/bin/env node
/*
 * watch-ci.js —— 盯着 GitHub Actions 的构建结果，直到出结论
 *
 * 为什么要它：构建日志端点需要鉴权，而"CI 绿了没有"是每轮都要问的问题。
 * 这里走 gh-proxy 读公开的运行状态，不需要任何凭据 —— 于是我不必让用户
 * 帮我复制日志，用户也不必替我盯着网页。
 *
 * 用法:  node tools/watch-ci.js [owner/repo] [超时秒数]
 * 退出码: 0 = 成功, 1 = 失败, 2 = 超时/读不到
 */

const https = require("https");

const REPO = process.argv[2] || "qsueh546734/health-monitor-app";
const TIMEOUT_S = Number(process.argv[3] || 900);
const INTERVAL_MS = 15000;

function get(path) {
  return new Promise((res) => {
    const req = https.get(
      "https://gh-proxy.com/https://api.github.com" + path,
      { headers: { "User-Agent": "watch-ci", Accept: "application/vnd.github+json" }, timeout: 20000 },
      (s) => {
        let b = "";
        s.on("data", (d) => (b += d));
        s.on("end", () => res({ code: s.statusCode, body: b }));
      }
    );
    req.on("error", (e) => res({ code: 0, body: e.message }));
    req.on("timeout", () => { req.destroy(); res({ code: 0, body: "timeout" }); });
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function latestRun() {
  const r = await get(`/repos/${REPO}/actions/runs?per_page=1`);
  if (r.code !== 200) return null;
  const j = JSON.parse(r.body);
  return j.workflow_runs && j.workflow_runs[0] ? j.workflow_runs[0] : null;
}

async function jobsOf(runId) {
  const r = await get(`/repos/${REPO}/actions/runs/${runId}/jobs`);
  if (r.code !== 200) return [];
  return JSON.parse(r.body).jobs || [];
}

(async () => {
  const started = Date.now();
  let lastKey = "";

  while ((Date.now() - started) / 1000 < TIMEOUT_S) {
    const run = await latestRun();
    if (!run) {
      console.log("读不到运行列表，重试中…");
      await sleep(INTERVAL_MS);
      continue;
    }

    const key = run.status + "/" + (run.conclusion || "-");
    if (key !== lastKey) {
      console.log(
        `run #${run.run_number}  ${run.status}/${run.conclusion || "-"}  head=${run.head_sha.slice(0, 7)}`
      );
      lastKey = key;
    }

    if (run.status === "completed") {
      const jobs = await jobsOf(run.id);
      for (const j of jobs) {
        console.log(`\njob: ${j.name}  ->  ${j.conclusion}`);
        for (const s of j.steps || []) {
          const mark = s.conclusion === "success" ? "OK  " : s.conclusion === "skipped" ? "--  " : "FAIL";
          console.log(`  ${mark} ${s.name}`);
        }
      }
      const ok = run.conclusion === "success";
      console.log(`\n${ok ? "CI GREEN" : "CI FAILED"}  (run #${run.run_number}, ${run.html_url})`);
      process.exit(ok ? 0 : 1);
    }

    await sleep(INTERVAL_MS);
  }

  console.log(`超时：${TIMEOUT_S}s 内没出结论`);
  process.exit(2);
})();
