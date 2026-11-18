$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$vendor = Join-Path $root 'vendor'
$assets = Join-Path $root 'src/main/assets/models'
New-Item -ItemType Directory -Force -Path $vendor, $assets | Out-Null

$packages = @(
    @{ Name = 'sherpa-static.aar'; Url = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar'; Sha256 = '07E042D97F3A6BD5B7FE74D4EEF0513DC01A714B16C15A903250095ABA43A575' },
    @{ Name = 'zipformer.tar.bz2'; Url = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01.tar.bz2'; Sha256 = 'B3B309F7CE4A737195FCC6963EA19B0653A7D3401580AF5AE0D3E284CBB71F0B' },
    @{ Name = 'paraformer.tar.bz2'; Url = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2'; Sha256 = 'DA92B3DB5218C5BE53AAD53E57D1B6E63E7FC98A0E054FBDD6DBE18E9C6B1450' },
    @{ Name = 'sensevoice.tar.bz2'; Url = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2'; Sha256 = '7D1EFA2138A65B0B488DF37F8B89E3D91A60676E416F515B952358D83DFD347E' }
)

foreach ($package in $packages) {
    $path = Join-Path $vendor $package.Name
    if (-not (Test-Path -LiteralPath $path)) {
        & curl.exe -L --fail --retry 3 --silent --show-error -o $path $package.Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $($package.Name)" }
    }
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if ($hash -ne $package.Sha256) { throw "SHA-256 mismatch: $($package.Name)" }
}

$unpacked = Join-Path $vendor 'unpacked'
New-Item -ItemType Directory -Force -Path $unpacked | Out-Null
foreach ($archive in @('zipformer.tar.bz2', 'paraformer.tar.bz2', 'sensevoice.tar.bz2')) {
    & tar -xf (Join-Path $vendor $archive) -C $unpacked
    if ($LASTEXITCODE -ne 0) { throw "Extraction failed: $archive" }
}

$copies = @(
    @{ Source = 'sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01'; Target = 'zipformer'; Files = @('model.int8.onnx', 'tokens.txt', 'bbpe.model') },
    @{ Source = 'sherpa-onnx-paraformer-zh-small-2024-03-09'; Target = 'paraformer'; Files = @('model.int8.onnx', 'tokens.txt') },
    @{ Source = 'sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17'; Target = 'sensevoice'; Files = @('model.int8.onnx', 'tokens.txt', 'LICENSE') }
)
foreach ($entry in $copies) {
    $target = Join-Path $assets $entry.Target
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    foreach ($file in $entry.Files) {
        Copy-Item -LiteralPath (Join-Path (Join-Path $unpacked $entry.Source) $file) -Destination $target -Force
    }
}
Write-Output 'Three ASR models and sherpa-onnx runtime are ready.'
