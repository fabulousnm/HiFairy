param()

$ErrorActionPreference = 'Stop'
$vendor = Join-Path $PSScriptRoot 'vendor'
$assets = @(
    @{ Relative = 'GPT-SoVITS/GPT_SoVITS/pretrained_models/fast_langdetect/lid.176.bin'; Url = 'https://github.com/LlmKira/fast-langdetect/releases/download/models-v1/lid.176.bin'; Algorithm = 'MD5'; Hash = '01810BC59C6A3D2B79C79E6336612F65' },
    @{ Relative = 'nltk_data/corpora/cmudict.zip'; Url = 'https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/corpora/cmudict.zip'; Algorithm = 'SHA256'; Hash = 'D07CCA47FD72AD32EA9D8AD1219F85301EEAF4568F8B6B73747506A71FB5AFD6' },
    @{ Relative = 'nltk_data/tokenizers/punkt.zip'; Url = 'https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/tokenizers/punkt.zip'; Algorithm = 'SHA256'; Hash = '51C3078994AEAF650BFC8E028BE4FB42B4A0D177D41C012B6A983979653660EC' },
    @{ Relative = 'nltk_data/tokenizers/punkt_tab.zip'; Url = 'https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/tokenizers/punkt_tab.zip'; Algorithm = 'SHA256'; Hash = 'E57F64187974277726A3417CA6F181EC5403676C717672EEF6A748A7B20E0106' },
    @{ Relative = 'nltk_data/taggers/averaged_perceptron_tagger.zip'; Url = 'https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/taggers/averaged_perceptron_tagger.zip'; Algorithm = 'SHA256'; Hash = 'E1F13CF2532DAADFD6F3BC481A49859F0B8EA6432CCDCD83E6A49A5F19008DE9' },
    @{ Relative = 'nltk_data/taggers/averaged_perceptron_tagger_eng.zip'; Url = 'https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/taggers/averaged_perceptron_tagger_eng.zip'; Algorithm = 'SHA256'; Hash = '6025F530624335C67D6547D44757B357B4E79BAE030A0383E9887A92C1718F0B' }
)

foreach ($asset in $assets) {
    $path = Join-Path $vendor $asset.Relative
    $folder = Split-Path -Parent $path
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $valid = (Test-Path -LiteralPath $path) -and ((Get-FileHash -LiteralPath $path -Algorithm $asset.Algorithm).Hash -eq $asset.Hash)
    if (-not $valid) {
        $temporary = "$path.download"
        & curl.exe -L --fail --retry 3 --silent --show-error $asset.Url -o $temporary
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $($asset.Relative)" }
        if ((Get-FileHash -LiteralPath $temporary -Algorithm $asset.Algorithm).Hash -ne $asset.Hash) { throw "Checksum mismatch: $($asset.Relative)" }
        Move-Item -LiteralPath $temporary -Destination $path -Force
    }
    if ($path.EndsWith('.zip')) { Expand-Archive -LiteralPath $path -DestinationPath $folder -Force }
    Write-Host "Ready: $($asset.Relative)"
}
