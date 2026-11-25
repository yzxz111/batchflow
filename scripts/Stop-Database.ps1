$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
if (-not $TaskflowPostgresBin) { throw 'PostgreSQL binaries not found. Set TASKFLOW_POSTGRES_BIN.' }
$databaseData = Join-Path $TaskflowProjectRoot '.runtime\postgres'
if (-not (Test-Path -LiteralPath (Join-Path $databaseData 'PG_VERSION'))) {
    Write-Output 'The project PostgreSQL cluster has not been initialized.'
    exit 0
}
$pgCtl = Join-Path $TaskflowPostgresBin 'pg_ctl.exe'
$statusOutput = & $pgCtl -D $databaseData status 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Output 'The project PostgreSQL cluster is already stopped.'
    exit 0
}
& $pgCtl -D $databaseData -m fast -w -t 30 stop
if ($LASTEXITCODE -ne 0) { throw 'Stopping the project PostgreSQL cluster failed.' }
Write-Output 'Project PostgreSQL stopped; data was preserved.'
