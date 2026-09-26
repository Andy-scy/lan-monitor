# soak.ps1 — 长稳测试采集器
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File soak.ps1 [-Minutes 60] [-Dir <agent目录>]
# 输出: soak.jsonl (1Hz 快照) + soak-process.csv (每 30s 的 Agent 进程占用)
param(
  [int]$Minutes = 60,
  [string]$Dir = "C:\VibeCoding\ZCode\lan-monitor\dist",
  [string]$OutDir = "C:\VibeCoding\ZCode\lan-monitor\tools\soak"
)
$ErrorActionPreference = "SilentlyContinue"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$cfg = Get-Content "$Dir\config.json" | ConvertFrom-Json
$token = $cfg.token
$jsonl = "$OutDir\soak.jsonl"
$csv = "$OutDir\soak-process.csv"
"timestamp,wsMB,cpuSeconds,handles" | Out-File $csv -Encoding utf8
$deadline = (Get-Date).AddMinutes($Minutes)
$nextProc = Get-Date
$n = 0
Write-Host "Soak started: $Minutes min -> $jsonl"
while ((Get-Date) -lt $deadline) {
  $t = Get-Date -Format "yyyy-MM-ddTHH:mm:ss"
  try {
    $r = Invoke-WebRequest -Uri "http://127.0.0.1:42710/api/snapshot?token=$token" -UseBasicParsing -TimeoutSec 3
    "$t|$($r.Content)" | Out-File $jsonl -Append -Encoding utf8
    $n++
  } catch {
    "$t|ERROR|$($_.Exception.Message)" | Out-File "$OutDir\soak-errors.log" -Append -Encoding utf8
  }
  if ((Get-Date) -ge $nextProc) {
    $p = Get-Process monitor -ErrorAction SilentlyContinue
    if ($p) {
      "$t,$([math]::Round($p.WorkingSet64/1MB,2)),$([math]::Round($p.TotalProcessorTime.TotalSeconds,3)),$($p.HandleCount)" |
        Out-File $csv -Append -Encoding utf8
    } else {
      "$t,AGENT_NOT_RUNNING,0,0" | Out-File $csv -Append -Encoding utf8
    }
    $nextProc = (Get-Date).AddSeconds(30)
  }
  Start-Sleep -Milliseconds 950
}
Write-Host "Soak done: $n snapshots"
