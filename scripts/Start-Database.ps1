$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
if (-not $TaskflowPostgresBin) { throw 'PostgreSQL binaries not found. Set TASKFLOW_POSTGRES_BIN to their bin directory.' }

$databasePort = 15439
$runtimeRoot = Join-Path $TaskflowProjectRoot '.runtime'
$databaseData = Join-Path $runtimeRoot 'postgres'
$databaseLogs = Join-Path $runtimeRoot 'logs'
New-Item -ItemType Directory -Force -Path $databaseData, $databaseLogs | Out-Null
$pgCtl = Join-Path $TaskflowPostgresBin 'pg_ctl.exe'

$alreadyRunning = $false
if (Test-Path -LiteralPath (Join-Path $databaseData 'PG_VERSION')) {
    $statusOutput = & $pgCtl -D $databaseData status 2>&1
    $alreadyRunning = ($LASTEXITCODE -eq 0)
}
if (-not $alreadyRunning) {
    $portProbe = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Loopback), $databasePort
    try { $portProbe.Start() }
    catch { throw "Port $databasePort is already in use by another process. No existing service was changed." }
    finally { $portProbe.Stop() }

    if (-not (Test-Path -LiteralPath (Join-Path $databaseData 'PG_VERSION'))) {
        & (Join-Path $TaskflowPostgresBin 'initdb.exe') -D $databaseData -U batchflow -A trust --encoding=UTF8 --locale=C
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL initdb failed.' }
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        # Dedicated demo cluster; only loopback clients can connect. Never expose this trust-auth profile.
        [System.IO.File]::AppendAllText((Join-Path $databaseData 'postgresql.conf'), "`n# BatchFlow local demo cluster`nlisten_addresses = '127.0.0.1'`nport = $databasePort`nmax_connections = 80`nshared_buffers = '64MB'`n", $utf8)
        [System.IO.File]::WriteAllText((Join-Path $databaseData 'pg_hba.conf'), "# Local demo only; server listens exclusively on 127.0.0.1.`nhost all all 127.0.0.1/32 trust`n", $utf8)
    }
    & $pgCtl -D $databaseData -l (Join-Path $databaseLogs 'postgres.log') -w -t 30 start
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL failed to start. See .runtime/logs/postgres.log.' }
} else {
    Write-Output 'The project PostgreSQL cluster is already running.'
}

foreach ($databaseName in @('batchflow', 'batchflow_test')) {
    $dbExists = & (Join-Path $TaskflowPostgresBin 'psql.exe') -h 127.0.0.1 -p $databasePort -U batchflow -d postgres -At -c "SELECT 1 FROM pg_database WHERE datname = '$databaseName'"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot connect to the project PostgreSQL cluster.' }
    if ($dbExists -ne '1') {
        & (Join-Path $TaskflowPostgresBin 'createdb.exe') -h 127.0.0.1 -p $databasePort -U batchflow -O batchflow -E UTF8 $databaseName
        if ($LASTEXITCODE -ne 0) { throw "Creating the $databaseName database failed." }
    }
}
Write-Output 'PostgreSQL ready: jdbc:postgresql://127.0.0.1:15439/batchflow (user=batchflow, local demo trust authentication).'
