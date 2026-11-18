$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '..\references\fairy-zzz-plugin\assets\voice'
$target = Join-Path $PSScriptRoot 'src\main\assets\fairy_voice'
if (-not (Test-Path -LiteralPath $source)) { throw "参考仓库语音素材缺失：$source" }
New-Item -ItemType Directory -Force -Path $target | Out-Null
@('greet.wav', 'greet-morning.wav', 'greet-noon.wav', 'greet-afternoon.wav', 'greet-evening.wav', 'greet-night.wav') | ForEach-Object {
    Copy-Item -LiteralPath (Join-Path $source $_) -Destination (Join-Path $target $_) -Force
}
Write-Output "Fairy 预录语音已复制到 $target"
