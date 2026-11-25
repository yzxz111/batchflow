# Requires env.ps1 to be dot-sourced first. All process operations are scoped
# to this project's jar, role, recorded PID and process start time.
function Get-TaskflowProcessRecord {
    param([ValidateSet('api', 'worker')][string]$Role)
    $recordFile = Join-Path $TaskflowProjectRoot ".runtime\processes\$Role.json"
    if (-not (Test-Path -LiteralPath $recordFile)) { return $null }
    return Get-Content -LiteralPath $recordFile -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Get-TaskflowOwnedProcess {
    param([ValidateSet('api', 'worker')][string]$Role)
    $record = Get-TaskflowProcessRecord -Role $Role
    if (-not $record) { return $null }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($record.pid)" -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    $expectedJar = Join-Path $TaskflowProjectRoot 'backend\target\batchflow-0.1.0.jar'
    $commandLine = [string]$process.CommandLine
    if ($process.Name -ne 'java.exe' -or -not $commandLine.Contains($expectedJar) -or -not $commandLine.Contains("--app.role=$Role")) {
        throw "Refusing to operate on PID $($record.pid): it does not match this project's $Role process."
    }
    $startedAt = (Get-Process -Id $record.pid -ErrorAction Stop).StartTime.ToUniversalTime()
    $recordedAt = [DateTime]::Parse($record.startedAt).ToUniversalTime()
    if ([Math]::Abs(($startedAt - $recordedAt).TotalSeconds) -gt 1) {
        throw "Refusing to operate on reused PID $($record.pid): process creation time differs."
    }
    return $process
}

function Test-TaskflowPortAvailable {
    param([int]$Port)
    $probe = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Loopback), $Port
    try { $probe.Start() }
    catch { throw "Port $Port is already in use. No existing process was changed." }
    finally { $probe.Stop() }
}

function Wait-TaskflowHealth {
    param([ValidateSet('api', 'worker')][string]$Role, [int]$TimeoutSeconds = 60)
    $port = if ($Role -eq 'api') { 18090 } else { 18091 }
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Get-TaskflowOwnedProcess -Role $Role)) { throw "$Role exited before becoming healthy. See .runtime/logs/$Role.err.log." }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2
            if ($health.status -eq 'UP' -and $health.role -eq $Role) { return $health }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "$Role did not become healthy within $TimeoutSeconds seconds. See .runtime/logs/."
}

function Start-TaskflowProcess {
    param([ValidateSet('api', 'worker')][string]$Role, [ValidateRange(0, 5000)][int]$BatchDelayMs = 200)
    $existing = Get-TaskflowOwnedProcess -Role $Role
    if ($existing) { Write-Output "$Role is already running as PID $($existing.ProcessId)."; return }
    $port = if ($Role -eq 'api') { 18090 } else { 18091 }
    Test-TaskflowPortAvailable -Port $port
    $jarFile = Join-Path $TaskflowProjectRoot 'backend\target\batchflow-0.1.0.jar'
    if (-not (Test-Path -LiteralPath $jarFile)) { throw 'Application jar not found. Run scripts/Start-Local.ps1 without -SkipBuild first.' }
    $storageDir = Join-Path $TaskflowProjectRoot '.runtime\storage'
    $logsDir = Join-Path $TaskflowProjectRoot '.runtime\logs'
    $recordsDir = Join-Path $TaskflowProjectRoot '.runtime\processes'
    New-Item -ItemType Directory -Force -Path $storageDir, $logsDir, $recordsDir | Out-Null
    $arguments = @('-jar', $jarFile, '--server.address=127.0.0.1', "--server.port=$port", "--app.role=$Role", '--app.queue-mode=database', "--app.storage-dir=$storageDir", "--app.worker.batch-delay-ms=$BatchDelayMs")
    # Windows Start-Process joins ArgumentList. Explicitly quote every argument,
    # including paths, so workspaces containing spaces remain valid.
    $quotedArguments = @($arguments | ForEach-Object { '"' + ($_ -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"' })
    $process = Start-Process -FilePath $TaskflowJava -ArgumentList $quotedArguments -WorkingDirectory $TaskflowProjectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logsDir "$Role.out.log") -RedirectStandardError (Join-Path $logsDir "$Role.err.log")
    $record = @{ pid = $process.Id; role = $Role; startedAt = $process.StartTime.ToUniversalTime().ToString('o'); jar = $jarFile; port = $port; batchDelayMs = $BatchDelayMs }
    $record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $recordsDir "$Role.json") -Encoding UTF8
    Write-Output "Started $Role as PID $($process.Id) on 127.0.0.1:$port."
}

function Stop-TaskflowProcess {
    param([ValidateSet('api', 'worker')][string]$Role)
    $process = Get-TaskflowOwnedProcess -Role $Role
    if ($process) {
        Stop-Process -Id $process.ProcessId -ErrorAction Stop
        try { Wait-Process -Id $process.ProcessId -Timeout 15 -ErrorAction Stop } catch {
            if (Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue) { throw }
        }
        Write-Output "Stopped project $Role process $($process.ProcessId)."
    } else { Write-Output "Project $Role is already stopped." }
    $recordFile = Join-Path $TaskflowProjectRoot ".runtime\processes\$Role.json"
    if (Test-Path -LiteralPath $recordFile) { Remove-Item -LiteralPath $recordFile }
}
