$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
. (Join-Path $PSScriptRoot 'local-processes.ps1')
foreach ($role in @('api', 'worker')) {
    $process = Get-TaskflowOwnedProcess -Role $role
    $port = if ($role -eq 'api') { 18090 } else { 18091 }
    if (-not $process) { Write-Output "$role process: STOPPED"; continue }
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 3
        Write-Output "$role process: PID=$($process.ProcessId), status=$($health.status), queue=$($health.queueMode), url=http://127.0.0.1:$port"
    } catch { Write-Output "$role process: PID=$($process.ProcessId), health=UNAVAILABLE" }
}
$databaseData = Join-Path $TaskflowProjectRoot '.runtime\postgres'
if ($TaskflowPostgresBin -and (Test-Path -LiteralPath (Join-Path $databaseData 'PG_VERSION'))) {
    & (Join-Path $TaskflowPostgresBin 'pg_ctl.exe') -D $databaseData status
} else { Write-Output 'Project PostgreSQL cluster: NOT INITIALIZED' }
