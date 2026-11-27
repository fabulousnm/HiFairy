param(
    [ValidateSet('Full', 'Gateway')][string]$Mode,
    [string]$PythonPath = 'G:\HiFairy-voice-env\Scripts\python.exe',
    [int]$Port = 8765,
    [switch]$StatusOnly
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$output = Join-Path $root 'output'
$vendor = Join-Path $root 'vendor'
$cloudflared = Join-Path $vendor 'cloudflared-windows-amd64.exe'
$tokenFile = Join-Path $vendor 'remote-test-token.txt'
$agentTokenFile = Join-Path $vendor 'agent-mobile-token.txt'
$localHealth = "http://127.0.0.1:$Port/health"
function Test-Health([string]$url, [int]$timeout = 4) {
    try { return (Invoke-RestMethod -Uri $url -TimeoutSec $timeout).ready -eq $true } catch { return $false }
}
function Find-TunnelUrl([string]$log) {
    if (-not (Test-Path -LiteralPath $log)) { return $null }
    $stream = [IO.File]::Open($log, 'Open', 'Read', 'ReadWrite')
    try {
        $reader = [IO.StreamReader]::new($stream)
        $matches = [regex]::Matches($reader.ReadToEnd(), 'https://[a-z0-9-]+\.trycloudflare\.com')
        if ($matches.Count) { return $matches[$matches.Count - 1].Value }
    } finally { $stream.Dispose() }
    return $null
}
function Stop-PreviousHiFairy([int]$targetPort) {
    $listener = Get-NetTCPConnection -LocalPort $targetPort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $listener) { return }
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    $expected = [regex]::Escape((Join-Path $root 'server.py')) + '|' + [regex]::Escape((Join-Path $root 'gateway_only.py'))
    if ($owner.CommandLine -notmatch $expected) { throw "端口 $targetPort 被非 HiFairy 进程占用，未自动关闭。" }
    Write-Host "正在接管旧 HiFairy 服务（端口 $targetPort）…"
    Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq 'cloudflared-windows-amd64.exe' -and
        $_.CommandLine -like "*--url http://127.0.0.1:$targetPort*"
    } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq 'python.exe' -and $_.CommandLine -match $expected -and
        ($_.CommandLine -match "--port $targetPort(?:\s|$)" -or ($targetPort -eq 8765 -and $_.CommandLine -notmatch '--port'))
    } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 700
    if (Get-NetTCPConnection -LocalPort $targetPort -State Listen -ErrorAction SilentlyContinue) {
        throw "旧服务仍占用端口 $targetPort，请检查。"
    }
}
if ($StatusOnly) {
    if (-not (Test-Health $localHealth)) { throw '本地服务尚未启动。' }
    $log = Get-ChildItem -LiteralPath $output -Filter 'tunnel-launch-*.err.log' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    $url = if ($log) { Find-TunnelUrl $log } else { $null }
    if (-not $url -or -not (Test-Health "$url/health")) { throw '公网通道尚未就绪。' }
    Write-Host "公网地址：$url"
    exit 0
}
if (-not $Mode) {
    Write-Host '1. Fairy 语音模型 + Agent 网关 + 公网通道'
    Write-Host '2. 仅 Agent 网关 + 公网通道（不加载语音模型）'
    $choice = Read-Host '请选择 1 或 2'
    $Mode = switch ($choice) { '1' { 'Full' } '2' { 'Gateway' } default { throw '请选择 1 或 2。' } }
}
if (-not (Test-Path -LiteralPath $PythonPath)) { throw "Python 环境不存在：$PythonPath" }
if (-not (Test-Path -LiteralPath $cloudflared)) { throw "cloudflared 不存在：$cloudflared" }
Stop-PreviousHiFairy $Port
New-Item -ItemType Directory -Path $output -Force | Out-Null
if (-not (Test-Path -LiteralPath $agentTokenFile)) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [IO.File]::WriteAllText($agentTokenFile, ([BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant())
}
# A Windows job kills all assigned children when this PowerShell process exits,
# including when its console window is closed without running finally.
Add-Type -TypeDefinition @'
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
public static class HiFairyJob {
  [StructLayout(LayoutKind.Sequential)] struct IO { public long A,B,C,D,E,F; }
  [StructLayout(LayoutKind.Sequential)] struct Basic { public long A,B; public uint Flags; public UIntPtr C,D; public uint E; public UIntPtr F; public uint G,H; }
  [StructLayout(LayoutKind.Sequential)] struct Extended { public Basic Basic; public IO Io; public UIntPtr A,B,C,D; }
  [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr p, string n);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetInformationJobObject(IntPtr j, int c, ref Extended x, uint size);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr j, IntPtr p);
  [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
  public static IntPtr Create() { var j=CreateJobObject(IntPtr.Zero,null); if(j==IntPtr.Zero) throw new System.ComponentModel.Win32Exception();
    var x=new Extended(); x.Basic.Flags=0x2000;
    if(!SetInformationJobObject(j,9,ref x,(uint)Marshal.SizeOf(x))) { CloseHandle(j); throw new System.ComponentModel.Win32Exception(); } return j; }
  public static void Add(IntPtr j,int pid) { using(var p=Process.GetProcessById(pid))
    if(!AssignProcessToJobObject(j,p.Handle)) throw new System.ComponentModel.Win32Exception(); }
  public static void Close(IntPtr j) { if(j!=IntPtr.Zero) CloseHandle(j); }
}
'@
$job = [HiFairyJob]::Create()
try {
    $runId = [guid]::NewGuid().ToString('N')
    $serverOut = Join-Path $output "server-launch-$runId.out.log"
    $serverErr = Join-Path $output "server-launch-$runId.err.log"
    $tunnelOut = Join-Path $output "tunnel-launch-$runId.out.log"
    $tunnelErr = Join-Path $output "tunnel-launch-$runId.err.log"
    $script = if ($Mode -eq 'Full') { 'server.py' } else { 'gateway_only.py' }
    Write-Host $(if ($Mode -eq 'Full') { '正在加载 Fairy 模型并预热…' } else { '正在启动轻量 Agent 网关…' })
    $server = Start-Process -FilePath $PythonPath -ArgumentList @('-u', (Join-Path $root $script), '--port', "$Port") -WorkingDirectory $root `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $serverOut -RedirectStandardError $serverErr
    [HiFairyJob]::Add($job, $server.Id)
    $ready = $false
    for ($i=0; $i -lt 90; $i++) {
        if (Test-Health $localHealth 2) { $ready=$true; break }
        if ($server.HasExited) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw "本地服务未就绪；查看 $serverErr" }
    Write-Host '正在启动临时公网通道…'
    $tunnel = Start-Process -FilePath $cloudflared `
        -ArgumentList @('tunnel','--url',"http://127.0.0.1:$Port",'--protocol','http2','--no-autoupdate') `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput $tunnelOut -RedirectStandardError $tunnelErr
    [HiFairyJob]::Add($job, $tunnel.Id)
    $url = $null
    for ($i=0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        if ($tunnel.HasExited) { break }
        $candidate = Find-TunnelUrl $tunnelErr
        if ($candidate -and (Test-Health "$candidate/health")) { $url=$candidate; break }
        if ($i -gt 0 -and $i % 5 -eq 0) { Write-Host "通道建立中，已等待约 $($i * 2) 秒…" }
    }
    if (-not $url) { throw "公网通道未就绪；查看 $tunnelErr" }
    Write-Host "公网地址：$url" -ForegroundColor Green
    Write-Host "Agent 地址：$url/agent" -ForegroundColor Green
    Write-Host "Agent 令牌：$([IO.File]::ReadAllText($agentTokenFile).Trim())" -ForegroundColor Yellow
    if ($Mode -eq 'Full') {
        if (-not (Test-Path -LiteralPath $tokenFile)) { throw "语音令牌不存在：$tokenFile" }
        Write-Host "语音服务地址：$(($url -replace '^https://','wss://') + '/stream')" -ForegroundColor Green
        Write-Host "语音访问令牌：$([IO.File]::ReadAllText($tokenFile).Trim())" -ForegroundColor Yellow
    } else { Write-Host '本次未加载 Fairy 模型，语音播放不可用。' }
    try {
        $agentToken = [IO.File]::ReadAllText($agentTokenFile).Trim()
        $bridge = Invoke-RestMethod -Uri "$url/agent/sessions" -Headers @{Authorization="Bearer $agentToken"} -TimeoutSec 5
        if ($null -eq $bridge.items) { throw 'empty' }
        Write-Host 'Agent 桥接已就绪。' -ForegroundColor Green
    } catch { Write-Warning 'DSH Agent 桥接暂不可用，请启动 DSH Desktop。' }
    Write-Host '按回车停止；直接关闭此窗口也会清理本次启动的进程。'
    [void][Console]::ReadLine()
} finally {
    [HiFairyJob]::Close($job)
    Write-Host '已关闭通道并释放本次启动的服务进程。'
}
