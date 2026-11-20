param(
    [string]$TokenFile = 'D:\dsh-data\hifairy-agent-probe.token',
    [string]$WorkspaceFile = 'D:\dsh-data\storages\workspace.json',
    [string]$Endpoint = 'http://127.0.0.1:18765/sessions'
)

$ErrorActionPreference = 'Stop'
$token = [System.IO.File]::ReadAllText($TokenFile).Trim()
$response = Invoke-RestMethod -Uri $Endpoint -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 8
$workspace = Get-Content -LiteralPath $WorkspaceFile -Raw | ConvertFrom-Json
$expected = @($workspace.tables.workspaces.PSObject.Properties.Value | ForEach-Object { $_.sessionIds } | Select-Object -Unique)
$observed = @($response.items | ForEach-Object { $_.sessionId })
$overlap = @($observed | Where-Object { $expected -contains $_ })
if ($overlap.Count -eq 0) { throw 'Host returned no session IDs known to the desktop workspace store' }
Write-Output "Host sessions=$($observed.Count); matching desktop workspace IDs=$($overlap.Count)"
