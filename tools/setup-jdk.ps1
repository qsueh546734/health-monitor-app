# Re-install the portable JDK 17 used for local (no-Android-SDK) verification.
#
# Why a script: this box has no system JDK, and PowerShell cannot do HTTPS here
# (schannel has no credentials), so the download is done by Node.js, which bundles OpenSSL.
# See .superpowers scratch notes / the project docs for the full story.
#
# NOTE: ASCII-only on purpose. Windows PowerShell 5.1 decodes a .ps1 without a UTF-8 BOM
# as ANSI, so non-ASCII text inside this file would break its own parsing.
#
# Usage:  powershell -NoProfile -ExecutionPolicy Bypass -File tools\setup-jdk.ps1
# Prints: the JDK_HOME path on success.

$ErrorActionPreference = 'Stop'

$base = Join-Path $env:TEMP 'dsh-tools'
$dir  = Join-Path $base 'jdk17'
New-Item -ItemType Directory -Force -Path $dir | Out-Null

# Already installed somewhere under a sibling temp root? Reuse it.
$parent = Split-Path $env:TEMP -Parent
$existing = $null
if ($parent -and (Test-Path $parent)) {
    foreach ($d in (Get-ChildItem $parent -Directory -Filter 'dsh-*' -ErrorAction SilentlyContinue)) {
        $t = Join-Path $d.FullName 'dsh-tools'
        if (-not (Test-Path $t)) { continue }
        $hit = Get-ChildItem $t -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit) { $existing = $hit.FullName; break }
    }
}
if ($existing) {
    $jdKHome = Split-Path (Split-Path $existing -Parent) -Parent
    Write-Host "Already present:"
    Write-Host "  JDK_HOME = $jdKHome"
    Write-Host "  javac    = $existing"
    exit 0
}

$zip = Join-Path $base 'jdk17.zip'
$url = 'https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'

# Download with Node: PowerShell's own HTTPS is blocked in this environment.
$js = @'
const https = require("https"), fs = require("fs");
const [url, out] = process.argv.slice(2);
(function dl(u, depth) {
  if (depth > 6) { console.log("TOO MANY REDIRECTS"); process.exit(1); }
  https.get(u, { timeout: 60000, headers: { "User-Agent": "node" } }, res => {
    if ([301,302,307,308].includes(res.statusCode)) { res.resume(); return dl(res.headers.location, depth + 1); }
    if (res.statusCode !== 200) { console.log("HTTP " + res.statusCode); process.exit(1); }
    const total = parseInt(res.headers["content-length"] || "0", 10);
    let got = 0, last = Date.now();
    const ws = fs.createWriteStream(out);
    res.on("data", d => {
      got += d.length;
      if (Date.now() - last > 5000) { last = Date.now();
        console.log((got/1048576).toFixed(1) + " / " + (total/1048576).toFixed(1) + " MB"); }
    });
    res.pipe(ws);
    ws.on("finish", () => console.log("DOWNLOADED " + got + " bytes"));
    ws.on("error", e => { console.log("WS ERR " + e.message); process.exit(1); });
  }).on("error", e => { console.log("REQ ERR " + e.message); process.exit(1); });
})(url, 0);
'@
$jsFile = Join-Path $base 'dl.js'
[System.IO.File]::WriteAllText($jsFile, $js, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Downloading JDK 17 from the Tsinghua mirror (~182 MB)..."
& node $jsFile $url $zip
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $zip)) { throw "download failed" }

Write-Host "Extracting..."
& tar.exe -xf $zip -C $dir
if ($LASTEXITCODE -ne 0) {
    Write-Host "tar failed, falling back to Expand-Archive..."
    Expand-Archive -Path $zip -DestinationPath $dir -Force
}

$javac = Get-ChildItem $dir -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $javac) { throw "javac not found after extraction" }

$jdKHome = Split-Path (Split-Path $javac.FullName -Parent) -Parent
Write-Host ""
Write-Host "Installed:"
Write-Host "  JDK_HOME = $jdKHome"
& $javac.FullName -version 2>&1 | ForEach-Object { Write-Host "  $_" }
Write-Host ""
Write-Host "Run the pure-Java tests with:"
Write-Host "  powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-tests.ps1"
