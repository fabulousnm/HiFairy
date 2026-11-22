param(
    [string]$PythonPath = 'G:\HiFairy-voice-env\Scripts\python.exe',
    [switch]$StatusOnly
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$output = Join-Path $root 'output'
$vendor = Join-Path $root 'vendor'
$cloudflared = Join-Path $vendor 'cloudflared-windows-amd64.exe'
$server = Join-Path $root 'server.py'
$tokenFile = Join-Path $vendor 'remote-test-token.txt'
$agentTokenFile = Join-Path $vendor 'agent-mobile-token.txt'
$serverLog = Join-Path $output 'server-launch.log'
$serverError = Join-Path $output 'server-launch-error.log'
$tunnelLog = Join-Path $output 'tunnel-launch.log'
$tunnelError = Join-Path $output 'tunnel-launch-error.log'
$localHealth = 'http://127.0.0.1:8765/health'

function Test-Health([string]$url) {
    try {
        $result = Invoke-RestMethod -Uri $url -TimeoutSec 8
        return $result.ready -eq $true
    } catch { return $false }
}

function Test-AgentBridge([string]$baseUrl) {
    try {
        $agentToken = [IO.File]::ReadAllText($agentTokenFile).Trim()
        $result = Invoke-RestMethod -Uri "$baseUrl/agent/sessions" -Headers @{ Authorization = "Bearer $agentToken" } -TimeoutSec 8
        return $null -ne $result.items
    } catch { return $false }
}

function Find-TunnelUrl {
    foreach ($log in @($tunnelError)) {
        if (-not (Test-Path -LiteralPath $log)) { continue }
        $stream = [IO.File]::Open($log, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try {
            $reader = New-Object IO.StreamReader($stream)
            $content = $reader.ReadToEnd()
        } finally { $stream.Dispose() }
        $matches = [regex]::Matches($content, 'https://[a-z0-9-]+\.trycloudflare\.com')
        if ($matches.Count -gt 0) { return $matches[$matches.Count - 1].Value }
    }
    return $null
}

New-Item -ItemType Directory -Path $output -Force | Out-Null
if (-not (Test-Path -LiteralPath $agentTokenFile)) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [IO.File]::WriteAllText($agentTokenFile, ([BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant())
}
if (-not $StatusOnly) {
    if (-not (Test-Path -LiteralPath $PythonPath)) { throw "Python 虚拟环境不存在：$PythonPath" }
    if (-not (Test-Path -LiteralPath $cloudflared)) { throw "cloudflared 不存在：$cloudflared" }
    if (-not (Test-Health $localHealth)) {
        Write-Host '正在启动 Fairy 模型服务并预热，首次可能需要约 30 秒…'
        Start-Process -FilePath $PythonPath -ArgumentList @('-u', $server) -WorkingDirectory $root -WindowStyle Hidden `
            -RedirectStandardOutput $serverLog -RedirectStandardError $serverError | Out-Null
        $ready = $false
        for ($i = 0; $i -lt 90; $i++) {
            if (Test-Health $localHealth) { $ready = $true; break }
            Start-Sleep -Seconds 2
        }
        if (-not $ready) { throw "模型服务未在 180 秒内就绪。查看 $serverError" }
    }
    Write-Host 'Fairy 模型服务已就绪。'
    $url = Find-TunnelUrl
    if (-not $url -or -not (Test-Health "$url/health") -or -not (Test-AgentBridge $url)) {
        Write-Host '正在启动临时公网通道…'
        # A stale connector may keep the old log handle open while its health page
        # still answers. Stop only this project's tunnel before replacing its log.
        Get-CimInstance Win32_Process | Where-Object {
            $_.Name -eq 'cloudflared-windows-amd64.exe' -and
            $_.CommandLine -like '*tunnel --url http://127.0.0.1:8765*'
        } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Milliseconds 500
        Start-Process -FilePath $cloudflared -ArgumentList @('tunnel', '--url', 'http://127.0.0.1:8765', '--protocol', 'http2', '--no-autoupdate') `
            -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $tunnelLog -RedirectStandardError $tunnelError | Out-Null
        $url = $null
        for ($i = 0; $i -lt 30; $i++) {
            Start-Sleep -Seconds 2
            $candidate = Find-TunnelUrl
            if ($candidate -and (Test-Health "$candidate/health") -and (Test-AgentBridge $candidate)) { $url = $candidate; break }
        }
        if (-not $url) { throw "公网通道未在 60 秒内就绪。查看 $tunnelError" }
    }
} else {
    if (-not (Test-Health $localHealth)) { throw '本地 Fairy 服务尚未启动。' }
    $url = Find-TunnelUrl
    if (-not $url -or -not (Test-Health "$url/health") -or -not (Test-AgentBridge $url)) { throw '公网通道尚未就绪。' }
}

if (-not (Test-Path -LiteralPath $tokenFile)) { throw "令牌文件不存在：$tokenFile" }
$token = [IO.File]::ReadAllText($tokenFile).Trim()
if ([string]::IsNullOrWhiteSpace($token)) { throw '令牌文件为空。' }
$socketUrl = ($url -replace '^https://', 'wss://') + '/stream'
Write-Host ''
Write-Host '请在 HiFairy 手机设置 → 语音播放 中填写：' -ForegroundColor Cyan
Write-Host "语音服务地址：$socketUrl" -ForegroundColor Green
Write-Host "访问令牌：$token" -ForegroundColor Yellow
Write-Host "Agent 地址：$url/agent" -ForegroundColor Green
Write-Host "Agent 令牌：$([IO.File]::ReadAllText($agentTokenFile).Trim())" -ForegroundColor Yellow
Write-Host '服务与通道在后台运行；Quick Tunnel 地址在重新建立后可能改变。'
