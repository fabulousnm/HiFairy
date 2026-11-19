$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '..\references\fairy-zzz-plugin\assets\voice'
$target = Join-Path $PSScriptRoot 'src\main\assets\fairy_voice'
if (-not (Test-Path -LiteralPath $source)) { throw "参考仓库语音素材缺失：$source" }
New-Item -ItemType Directory -Force -Path $target | Out-Null
@('greet.wav', 'greet-morning.wav', 'greet-noon.wav', 'greet-afternoon.wav', 'greet-evening.wav', 'greet-night.wav') | ForEach-Object {
    Copy-Item -LiteralPath (Join-Path $source $_) -Destination (Join-Path $target $_) -Force
}
Write-Output "Fairy 预录语音已复制到 $target"

$vendor = Join-Path $PSScriptRoot 'vendor'
$modelAssets = Join-Path $PSScriptRoot 'src\main\assets\zipvoice'
New-Item -ItemType Directory -Force -Path $vendor, $modelAssets | Out-Null
$packages = @(
    @{ Name = 'zipvoice.tar.bz2'; Url = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2'; Sha256 = '77219C8B40F4EE8D73A7F902305FF6C1128EF9B54461C41B4CA6ED890B6C2803' },
    @{ Name = 'vocos_24khz.onnx'; Url = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx'; Sha256 = 'BCB3B970E384161C4D634F0BB9E999FF1C471B34C9BC0B1049A5014065ED3CC0' }
)
foreach ($package in $packages) {
    $path = Join-Path $vendor $package.Name
    if (-not (Test-Path -LiteralPath $path)) {
        & curl.exe -L --fail --retry 3 --silent --show-error -o $path $package.Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $($package.Name)" }
    }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $package.Sha256) { throw "SHA-256 mismatch: $($package.Name)" }
}

$unpacked = Join-Path $vendor 'sherpa-onnx-zipvoice-distill-int8-zh-en-emilia'
if (-not (Test-Path -LiteralPath (Join-Path $unpacked 'encoder.int8.onnx'))) {
    & tar -xf (Join-Path $vendor 'zipvoice.tar.bz2') -C $vendor
    if ($LASTEXITCODE -ne 0) { throw 'ZipVoice extraction failed' }
}
@('encoder.int8.onnx', 'decoder.int8.onnx', 'tokens.txt', 'lexicon.txt') | ForEach-Object {
    Copy-Item -LiteralPath (Join-Path $unpacked $_) -Destination (Join-Path $modelAssets $_) -Force
}
Copy-Item -LiteralPath (Join-Path $vendor 'vocos_24khz.onnx') -Destination (Join-Path $modelAssets 'vocos_24khz.onnx') -Force

$convert = @'
import audioop, wave, sys
source, target = sys.argv[1:3]
with wave.open(source, 'rb') as reader:
    assert reader.getnchannels() == 1 and reader.getsampwidth() == 2
    frames = reader.readframes(reader.getnframes())
    converted, _ = audioop.ratecv(frames, 2, 1, reader.getframerate(), 24000, None)
with wave.open(target, 'wb') as writer:
    writer.setnchannels(1)
    writer.setsampwidth(2)
    writer.setframerate(24000)
    writer.writeframes(converted)
'@
$convert | & python - (Join-Path $source 'greet-noon.wav') (Join-Path $modelAssets 'fairy-reference.wav')
if ($LASTEXITCODE -ne 0) { throw 'Fairy reference audio conversion failed' }
Write-Output "ZipVoice inference assets are ready in $modelAssets"
