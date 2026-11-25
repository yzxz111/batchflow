param(
    [Parameter(Mandatory = $true)][string]$ReportDirectory,
    [string]$Destination
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$source = (Resolve-Path -LiteralPath $ReportDirectory -ErrorAction Stop).Path
$summaryFile = Join-Path $source 'report.json'
if (-not (Test-Path -LiteralPath $summaryFile)) { throw 'Source report.json is missing.' }
$summary = Get-Content -LiteralPath $summaryFile -Raw -Encoding UTF8 | ConvertFrom-Json
if ($summary.status -ne 'PASSED') { throw 'Only a completed PASSED local verification can be exported as delivery evidence.' }
if (-not $Destination) { $Destination = Join-Path $projectRoot ('docs\evidence\' + (Split-Path -Leaf $source)) }
$Destination = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $Destination) { throw 'Evidence destination already exists; refusing to overwrite.' }
if ($Destination.StartsWith($source + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Evidence destination must not be inside its source.'
}
$snapshotPath = Join-Path $source 'source-files.json'
if (-not (Test-Path -LiteralPath $snapshotPath)) { throw 'Verification-time source snapshot is missing.' }
$sourceFiles = @(Get-Content -LiteralPath $snapshotPath -Raw -Encoding UTF8 | ConvertFrom-Json)
foreach ($file in $sourceFiles) {
    $actual = Join-Path $projectRoot $file.path
    if (-not (Test-Path -LiteralPath $actual) -or (Get-FileHash -LiteralPath $actual -Algorithm SHA256).Hash -ne $file.sha256) {
        throw "Verified source/config file changed since verification: $($file.path). Re-run verification before export."
    }
}
New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
Copy-Item -LiteralPath $source -Destination $Destination -Recurse
$prefix = $Destination.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$files = @(Get-ChildItem -LiteralPath $Destination -Recurse -File | Sort-Object FullName | ForEach-Object {
    [ordered]@{ path = $_.FullName.Substring($prefix.Length).Replace('\', '/'); sizeBytes = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
})
[ordered]@{
    schemaVersion = 1; exportedAt = [DateTime]::UtcNow.ToString('o'); status = $summary.status
    verificationStartedAt = $summary.startedAt; verificationFinishedAt = $summary.finishedAt
    sourceRevisionAtExport = (& git -C $projectRoot rev-parse HEAD).Trim()
    sourceHasChangesAtExport = [bool](& git -C $projectRoot status --porcelain)
    note = 'Source hashes were captured before verification, checked again after verification and matched at export. Reports may precede the final documentation commit. Runtime databases, tools and cookies are excluded.'
    files = $files; sourceFiles = $sourceFiles
} | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $Destination 'manifest.json') -Encoding UTF8
Write-Output "Evidence exported: $Destination"
