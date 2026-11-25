param([switch]$SkipBuild, [ValidateRange(0, 5000)][int]$BatchDelayMs = 200)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
. (Join-Path $PSScriptRoot 'local-processes.ps1')
Push-Location $TaskflowProjectRoot
try {
    & (Join-Path $PSScriptRoot 'Start-Database.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Database startup failed.' }
    $existingApi = Get-TaskflowOwnedProcess -Role api
    $existingWorker = Get-TaskflowOwnedProcess -Role worker
    if (-not $SkipBuild -and ($existingApi -or $existingWorker)) {
        throw 'Application processes are already running. Stop them with scripts/Stop-Local.ps1 before rebuilding, or use -SkipBuild to start only missing processes.'
    }
    if (-not $SkipBuild) {
        $pnpmCommand = Get-Command pnpm.cmd -ErrorAction Stop
        Push-Location (Join-Path $TaskflowProjectRoot 'web')
        try {
            if (Test-Path -LiteralPath 'pnpm-lock.yaml') { & $pnpmCommand.Source install --frozen-lockfile } else { & $pnpmCommand.Source install }
            if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
            & $pnpmCommand.Source build
            if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
        } finally { Pop-Location }
        & $TaskflowMaven -f backend/pom.xml package
        if ($LASTEXITCODE -ne 0) { throw 'Backend tests or packaging failed.' }
    }
    $env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:15439/batchflow'
    $env:DATABASE_USER = 'batchflow'
    $env:DATABASE_PASSWORD = ''
    Start-TaskflowProcess -Role api -BatchDelayMs $BatchDelayMs
    $apiHealth = Wait-TaskflowHealth -Role api
    Start-TaskflowProcess -Role worker -BatchDelayMs $BatchDelayMs
    $workerHealth = Wait-TaskflowHealth -Role worker
    Write-Output 'BatchFlow is ready: http://127.0.0.1:18090'
    Write-Output 'API and Worker are independent background processes. Logs: .runtime/logs/'
} finally { Pop-Location }
