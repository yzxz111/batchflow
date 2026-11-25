[CmdletBinding()]
param([string]$ReportDirectory)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
if ([string]::IsNullOrWhiteSpace($ReportDirectory)) {
    $ReportDirectory = Join-Path $projectRoot ".runtime\evidence\rabbitmq\$runId"
}
$ReportDirectory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ReportDirectory)
if (Test-Path -LiteralPath $ReportDirectory) {
    throw 'ReportDirectory already exists. Choose a new directory; previous evidence will not be overwritten.'
}
$null = [System.IO.Directory]::CreateDirectory((Split-Path -Parent $ReportDirectory))
$null = New-Item -ItemType Directory -Path $ReportDirectory -ErrorAction Stop
$reportPath = Join-Path $ReportDirectory 'report.json'
$nodeEvidencePath = Join-Path $ReportDirectory 'tasks.json'
$report = [ordered]@{
    schemaVersion = 1
    runId = $runId
    status = 'RUNNING'
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    finishedAtUtc = $null
    queueMode = 'rabbitmq'
    checks = @()
    nativeCommands = @()
    tasks = $null
    recovery = $null
    queue = $null
    outboxPendingAfterRun = $null
    failure = $null
    cleanupFailures = @()
}
$stage = 'initialization'
$failure = $null
$verificationPassed = $false
$startedRoles = New-Object 'System.Collections.Generic.HashSet[string]'
$rmqProc = $null
$brokerStarted = $false

function Save-VerificationReport {
    $json = $report | ConvertTo-Json -Depth 12
    [System.IO.File]::WriteAllText($reportPath, $json, (New-Object System.Text.UTF8Encoding($false)))
}

function Assert-Verification {
    param([string]$Name, [bool]$Condition)
    $report.checks += [ordered]@{ name = $Name; passed = $Condition; checkedAtUtc = [DateTime]::UtcNow.ToString('o') }
    if (-not $Condition) { throw "Verification assertion failed: $Name" }
}

function Invoke-CheckedNative {
    param([string]$Name, [string]$FilePath, [string[]]$Arguments = @())
    # Never archive argv, environment, stderr or arbitrary command output.
    $entry = [ordered]@{ name = $Name; exitCode = $null; completed = $false }
    $report.nativeCommands += $entry
    $global:LASTEXITCODE = 0
    $output = @(& $FilePath @Arguments)
    $code = $global:LASTEXITCODE
    $entry.exitCode = $code
    $entry.completed = $true
    if ($code -ne 0) { throw "$Name failed with exit code $code." }
    return $output
}

Save-VerificationReport
try {
$stage = 'environment'
. (Join-Path $PSScriptRoot 'env.ps1')
. (Join-Path $PSScriptRoot 'local-processes.ps1')

if (-not $TaskflowErlangHome -or -not $TaskflowRabbitHome) {
    throw 'Portable Erlang or RabbitMQ under .tools/ is required for Verify-RabbitMQ.ps1.'
}

Invoke-CheckedNative -Name 'start-project-database' -FilePath (Join-Path $PSScriptRoot 'Start-Database.ps1') | Out-Null

$existingApi = Get-TaskflowOwnedProcess -Role api
$existingWorker = Get-TaskflowOwnedProcess -Role worker
if ($existingApi -or $existingWorker) {
    throw 'Stop existing API/Worker processes with scripts/Stop-Local.ps1 before running Verify-RabbitMQ.ps1.'
}

$jarFile = Join-Path $TaskflowProjectRoot 'backend\target\batchflow-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jarFile)) {
    throw 'Application jar not found. Build first via Start-Local.ps1 or mvn package.'
}

$rmqBase = Join-Path $TaskflowProjectRoot '.runtime\rabbitmq'
$logsDir = Join-Path $TaskflowProjectRoot '.runtime\logs'
$storageDir = Join-Path $TaskflowProjectRoot '.runtime\storage'
$recordsDir = Join-Path $TaskflowProjectRoot '.runtime\processes'
New-Item -ItemType Directory -Force -Path $rmqBase, $logsDir, $storageDir, $recordsDir | Out-Null

$env:RABBITMQ_BASE = $rmqBase
$env:RABBITMQ_NODENAME = 'rabbit@localhost'
$env:RABBITMQ_NODE_IP_ADDRESS = '127.0.0.1'
$env:RABBITMQ_NODE_PORT = '5672'

$rmqOut = Join-Path $logsDir 'rabbitmq.out.log'
$rmqErr = Join-Path $logsDir 'rabbitmq.err.log'
$rmqBat = Join-Path $TaskflowRabbitHome 'sbin\rabbitmq-server.bat'
$rmqCtl = Join-Path $TaskflowRabbitHome 'sbin\rabbitmqctl.bat'

function Start-RmqAppRole {
    param([ValidateSet('api', 'worker')][string]$Role, [int]$BatchDelayMs = 200, [int]$LeaseSeconds = 15, [int]$PollMs = 500)
    $port = if ($Role -eq 'api') { 18090 } else { 18091 }
    Test-TaskflowPortAvailable -Port $port
    $arguments = @(
        '-jar', $jarFile,
        '--server.address=127.0.0.1',
        "--server.port=$port",
        "--app.role=$Role",
        '--app.queue-mode=rabbitmq',
        "--app.storage-dir=$storageDir",
        "--app.worker.batch-delay-ms=$BatchDelayMs",
        "--app.worker.lease-seconds=$LeaseSeconds",
        "--app.worker.poll-ms=$PollMs",
        '--spring.rabbitmq.host=127.0.0.1',
        '--spring.rabbitmq.port=5672',
        '--spring.rabbitmq.username=batchflow',
        '--spring.rabbitmq.password=batchflow-local'
    )
    $quoted = @($arguments | ForEach-Object { '"' + ($_ -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"' })
    $p = Start-Process -FilePath $TaskflowJava -ArgumentList $quoted -WorkingDirectory $TaskflowProjectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logsDir "$Role.out.log") -RedirectStandardError (Join-Path $logsDir "$Role.err.log")
    @{ pid = $p.Id; role = $Role; startedAt = $p.StartTime.ToUniversalTime().ToString('o'); jar = $jarFile; port = $port; queueMode = 'rabbitmq' } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $recordsDir "$Role.json") -Encoding UTF8
    $null = $startedRoles.Add($Role)
    return Wait-TaskflowHealth -Role $Role
}

$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:15439/batchflow'
$env:DATABASE_USER = 'batchflow'
$env:DATABASE_PASSWORD = ''

$stage = 'broker-startup'
Test-TaskflowPortAvailable -Port 5672
$rmqProc = Start-Process -FilePath $rmqBat -WindowStyle Hidden -PassThru -RedirectStandardOutput $rmqOut -RedirectStandardError $rmqErr
$brokerStarted = $true
    $amqpReady = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 1
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $tcp.Connect('127.0.0.1', 5672)
            $tcp.Close()
            $amqpReady = $true
            break
        } catch { }
    }
    if (-not $amqpReady) { throw 'RabbitMQ did not open port 5672 within 60 seconds.' }
    Invoke-CheckedNative -Name 'rabbitmq-await-startup' -FilePath $rmqCtl -Arguments @('await_startup') | Out-Null

    $users = Invoke-CheckedNative -Name 'rabbitmq-list-users' -FilePath $rmqCtl -Arguments @('list_users', '--no-table-headers')
    if (-not ($users -match '^batchflow\s')) {
        Invoke-CheckedNative -Name 'rabbitmq-create-demo-user' -FilePath $rmqCtl -Arguments @('add_user', 'batchflow', 'batchflow-local') | Out-Null
        Invoke-CheckedNative -Name 'rabbitmq-tag-demo-user' -FilePath $rmqCtl -Arguments @('set_user_tags', 'batchflow', 'administrator') | Out-Null
    }
    Invoke-CheckedNative -Name 'rabbitmq-set-demo-permissions' -FilePath $rmqCtl -Arguments @('set_permissions', '-p', '/', 'batchflow', '.*', '.*', '.*') | Out-Null

    $stage = 'application-startup'
    $apiHealth = Start-RmqAppRole -Role api
    $workerHealth = Start-RmqAppRole -Role worker -BatchDelayMs 250 -LeaseSeconds 8 -PollMs 250
    Assert-Verification -Name 'api-and-worker-use-rabbitmq' -Condition ($apiHealth.queueMode -eq 'rabbitmq' -and $workerHealth.queueMode -eq 'rabbitmq')

    # Verify RabbitMQ queue/exchange declaration and run 3 real broker scenarios via Node
    $verifyJs = @'
import { writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

const BASE = 'http://127.0.0.1:18090';
const evidencePath = process.argv[2];
const evidence = { createdTasks: [], single: null, concurrent: [], recovery: null };
let writes = Promise.resolve();
async function saveEvidence() {
  const snapshot = JSON.stringify(evidence, null, 2);
  writes = writes.then(() => writeFile(evidencePath, snapshot, 'utf8'));
  await writes;
}
async function request(path, options = {}) {
  const response = await fetch(`${BASE}${path}`, { ...options, signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${path}`);
  return response;
}

async function uploadAndCreate(name, rows, invalidEvery, batchSize) {
  const prefix = `RMQ-${randomUUID().slice(0, 8).toUpperCase()}`;
  const lines = ['\uFEFFsku,name,category,price,stock\r\n'];
  let invalid = 0;
  for (let i = 1; i <= rows; i++) {
    const bad = invalidEvery > 0 && i % invalidEvery === 0;
    if (bad) invalid++;
    lines.push(`${prefix}-${i},"RMQ商品${i}",办公,${bad ? '-1' : '15.00'},5\r\n`);
  }
  const form = new FormData();
  form.append('file', new Blob([lines.join('')], { type: 'text/csv' }), `${name}.csv`);
  const upResp = await request('/api/uploads', { method: 'POST', body: form });
  const upload = await upResp.json();
  const taskResp = await request('/api/tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
    body: JSON.stringify({
      name, uploadId: upload.uploadId,
      mapping: { sku: 'sku', name: 'name', category: 'category', price: 'price', stock: 'stock' },
      batchSize, maxAttempts: 3,
    }),
  });
  const task = await taskResp.json();
  evidence.createdTasks.push({ id: task.id, name, totalRows: rows, expectedValid: rows - invalid, expectedError: invalid, prefix });
  await saveEvidence();
  return { task, expectedValid: rows - invalid, expectedError: invalid, prefix };
}

async function waitTerminal(id, timeoutMs = 60000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const r = await request(`/api/tasks/${encodeURIComponent(id)}`);
    const d = await r.json();
    if (['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED'].includes(d.status)) return d;
    await new Promise(res => setTimeout(res, 250));
  }
  throw new Error(`Task ${id} timed out`);
}

// Scenario A: single RabbitMQ task
const single = await uploadAndCreate('RMQ-单任务验证', 120, 25, 40);
const singleDone = await waitTerminal(single.task.id);
evidence.single = { id: singleDone.id, status: singleDone.status, processedRows: singleDone.processedRows, successRows: singleDone.successRows, errorRows: singleDone.errorRows, checkpoint: singleDone.checkpoint };
await saveEvidence();
if (singleDone.status !== 'COMPLETED_WITH_ERRORS' || singleDone.processedRows !== 120 || singleDone.successRows !== single.expectedValid || singleDone.errorRows !== single.expectedError || singleDone.checkpoint !== 120) {
  throw new Error(`Scenario A mismatch: ${JSON.stringify(singleDone)}`);
}

// Four concurrent submissions; deterministic lock contention is tested separately.
const concurrent = await Promise.all([
  uploadAndCreate('RMQ-并发1', 200, 0, 50),
  uploadAndCreate('RMQ-并发2', 200, 0, 50),
  uploadAndCreate('RMQ-并发3', 200, 0, 50),
  uploadAndCreate('RMQ-并发4', 200, 0, 50),
]);
const concurrentDone = await Promise.all(concurrent.map(c => waitTerminal(c.task.id, 90000)));
evidence.concurrent = concurrentDone.map(d => ({ id: d.id, status: d.status, processedRows: d.processedRows, successRows: d.successRows, errorRows: d.errorRows, checkpoint: d.checkpoint }));
await saveEvidence();
for (const d of concurrentDone) {
  if (d.status !== 'COMPLETED' || d.processedRows !== 200 || d.successRows !== 200 || d.errorRows !== 0 || d.checkpoint !== 200) {
    throw new Error(`Scenario B failed (possible dropped message): ${JSON.stringify(d)}`);
  }
}

// Output recovery task created for PowerShell worker-kill test
const rec = await uploadAndCreate('RMQ-故障恢复实测', 600, 25, 100);
evidence.recovery = { id: rec.task.id, prefix: rec.prefix, expectedValid: rec.expectedValid, expectedError: rec.expectedError };
await saveEvidence();
'@

    $stage = 'broker-single-and-concurrent-tasks'
    $tmpVerify = Join-Path $ReportDirectory 'verify-rmq.mjs'
    Set-Content -LiteralPath $tmpVerify -Value $verifyJs -Encoding UTF8
        Invoke-CheckedNative -Name 'rabbitmq-node-task-scenarios' -FilePath (Get-Command node -ErrorAction Stop).Source -Arguments @($tmpVerify, $nodeEvidencePath) | Out-Null
        $info = Get-Content -LiteralPath $nodeEvidencePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $report.tasks = $info
        Assert-Verification -Name 'single-task-and-four-concurrent-tasks-completed' -Condition ($info.single.status -eq 'COMPLETED_WITH_ERRORS' -and @($info.concurrent).Count -eq 4)

        # Scenario C: wait until recovery task hits >= 100 processedRows, kill worker, restart in rabbitmq mode, verify recovery
        $stage = 'worker-stop-and-recovery'
        $mid = $null
        for ($i = 0; $i -lt 40; $i++) {
            $t = Invoke-RestMethod -Uri "http://127.0.0.1:18090/api/tasks/$($info.recovery.id)" -TimeoutSec 10
            if ($t.status -eq 'RUNNING' -and $t.processedRows -ge 100 -and $t.processedRows -lt 600) {
                $mid = $t; break
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $mid) { throw 'Recovery task did not enter mid-flight RUNNING state.' }
        $report.recovery = [ordered]@{
            taskId = $info.recovery.id
            observedBeforeStop = @{ checkpoint = $mid.checkpoint; processedRows = $mid.processedRows; observedAtUtc = [DateTime]::UtcNow.ToString('o') }
            persistedAfterStop = $null
            recoveredCheckpointFromEvent = $null
            final = $null
            productCount = $null
            events = @()
        }

        Stop-TaskflowProcess -Role worker | Out-Null
        $stopped = Invoke-RestMethod -Uri "http://127.0.0.1:18090/api/tasks/$($info.recovery.id)" -TimeoutSec 10
        $report.recovery.persistedAfterStop = @{ checkpoint = $stopped.checkpoint; processedRows = $stopped.processedRows; observedAtUtc = [DateTime]::UtcNow.ToString('o') }
        Assert-Verification -Name 'worker-stopped-before-task-finished' -Condition ($stopped.status -eq 'RUNNING' -and $stopped.checkpoint -ge $mid.checkpoint -and $stopped.checkpoint -lt 600)
        $null = Start-RmqAppRole -Role worker -BatchDelayMs 100 -LeaseSeconds 8 -PollMs 250

        $recovered = $null
        for ($i = 0; $i -lt 80; $i++) {
            $t = Invoke-RestMethod -Uri "http://127.0.0.1:18090/api/tasks/$($info.recovery.id)" -TimeoutSec 10
            if ($t.status -in @('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')) {
                $recovered = $t; break
            }
            Start-Sleep -Milliseconds 500
        }
        if ($recovered) {
            $report.recovery.final = @{ status = $recovered.status; processedRows = $recovered.processedRows; successRows = $recovered.successRows; errorRows = $recovered.errorRows; checkpoint = $recovered.checkpoint; attempt = $recovered.attempt }
            $report.recovery.events = @($recovered.events | Select-Object id, type, createdAt)
        }
        Assert-Verification -Name 'recovery-terminal-counts-and-attempt' -Condition ($null -ne $recovered -and $recovered.status -eq 'COMPLETED_WITH_ERRORS' -and $recovered.processedRows -eq 600 -and $recovered.checkpoint -eq 600 -and $recovered.successRows -eq $info.recovery.expectedValid -and $recovered.errorRows -eq $info.recovery.expectedError -and $recovered.attempt -ge 2)
        $recoveryEvents = @($recovered.events | Where-Object type -eq 'RECOVERED')
        Assert-Verification -Name 'recovered-event-present' -Condition ($recoveryEvents.Count -gt 0)
        $checkpointMatch = [regex]::Match([string]$recoveryEvents[-1].message, '从第\s*(\d+)\s*行')
        Assert-Verification -Name 'recovered-event-checkpoint-readable' -Condition $checkpointMatch.Success
        $report.recovery.recoveredCheckpointFromEvent = [long]$checkpointMatch.Groups[1].Value
        Assert-Verification -Name 'recovered-from-persisted-checkpoint' -Condition ($report.recovery.recoveredCheckpointFromEvent -eq $stopped.checkpoint)
        $productsCheck = Invoke-RestMethod -Uri "http://127.0.0.1:18090/api/products?q=$($info.recovery.prefix)&size=1" -TimeoutSec 10
        $report.recovery.productCount = $productsCheck.total
        Assert-Verification -Name 'recovery-products-unique-and-complete' -Condition ($productsCheck.total -eq $info.recovery.expectedValid)

        $stage = 'queue-and-outbox-drained'
        for ($i = 0; $i -lt 20; $i++) {
            $queues = Invoke-CheckedNative -Name 'rabbitmq-list-queues' -FilePath $rmqCtl -Arguments @('list_queues', '-p', '/', 'name', 'messages', 'messages_ready', 'messages_unacknowledged', '--no-table-headers')
            $targetQueues = @($queues | ForEach-Object {
                $m = [regex]::Match([string]$_, '^batchflow\.imports\s+(\d+)\s+(\d+)\s+(\d+)\s*$')
                if ($m.Success) { [pscustomobject]@{ name = 'batchflow.imports'; messages = [long]$m.Groups[1].Value; messagesReady = [long]$m.Groups[2].Value; messagesUnacknowledged = [long]$m.Groups[3].Value } }
            })
            if ($targetQueues.Count -eq 1) {
                $report.queue = $targetQueues[0]
                if ($report.queue.messages -eq 0 -and $report.queue.messagesReady -eq 0 -and $report.queue.messagesUnacknowledged -eq 0) { break }
            }
            Start-Sleep -Milliseconds 250
        }
        Assert-Verification -Name 'target-queue-exists-exactly-once' -Condition ($targetQueues.Count -eq 1)
        Assert-Verification -Name 'target-queue-ready-and-unacknowledged-are-zero' -Condition ($report.queue.messages -eq 0 -and $report.queue.messagesReady -eq 0 -and $report.queue.messagesUnacknowledged -eq 0)
        $outboxLines = @(Invoke-CheckedNative -Name 'postgres-outbox-pending-count' -FilePath (Join-Path $TaskflowPostgresBin 'psql.exe') -Arguments @('-h', '127.0.0.1', '-p', '15439', '-U', 'batchflow', '-d', 'batchflow', '-X', '-v', 'ON_ERROR_STOP=1', '-At', '-c', 'SELECT count(*) FROM task_outbox WHERE published_at IS NULL;'))
        $outboxText = ($outboxLines -join "`n").Trim()
        Assert-Verification -Name 'outbox-count-is-valid-integer' -Condition ($outboxText -match '^\d+$')
        $report.outboxPendingAfterRun = [long]$outboxText
        Assert-Verification -Name 'outbox-pending-is-zero' -Condition ($report.outboxPendingAfterRun -eq 0)
        $verificationPassed = $true
} catch {
    $failure = $_
    # Arbitrary exception text can contain command lines or environment secrets.
    $report.failure = @{ stage = $stage; exceptionType = $_.Exception.GetType().FullName; message = 'Verification failed; consult local console/process logs. Sensitive values are not archived.' }
} finally {
    foreach ($role in @('worker', 'api')) {
        if ($startedRoles.Contains($role)) {
            try { Stop-TaskflowProcess -Role $role | Out-Null }
            catch { $report.cleanupFailures += @{ stage = "stop-$role"; exceptionType = $_.Exception.GetType().FullName } }
        }
    }
    if ($brokerStarted) {
        try { Invoke-CheckedNative -Name 'rabbitmq-stop' -FilePath $rmqCtl -Arguments @('stop') | Out-Null }
        catch { $report.cleanupFailures += @{ stage = 'stop-broker'; exceptionType = $_.Exception.GetType().FullName } }
        if ($rmqProc -and -not $rmqProc.HasExited) {
            Start-Sleep -Seconds 2
            if (-not $rmqProc.HasExited) {
                try { Stop-Process -Id $rmqProc.Id -Force -ErrorAction Stop }
                catch { $report.cleanupFailures += @{ stage = 'stop-broker-launcher'; exceptionType = $_.Exception.GetType().FullName } }
            }
        }
    }
    if (Test-Path -LiteralPath $nodeEvidencePath) {
        try { $report.tasks = Get-Content -LiteralPath $nodeEvidencePath -Raw -Encoding UTF8 | ConvertFrom-Json }
        catch { $report.cleanupFailures += @{ stage = 'read-partial-task-evidence'; exceptionType = $_.Exception.GetType().FullName } }
    }
    $report.finishedAtUtc = [DateTime]::UtcNow.ToString('o')
    $report.status = if ($verificationPassed -and -not $failure -and $report.cleanupFailures.Count -eq 0) { 'PASSED' } else { 'FAILED' }
    Save-VerificationReport
}
Write-Output "RabbitMQ verification $($report.status). Evidence: $reportPath"
if ($failure) { throw $failure }
if ($report.status -ne 'PASSED') { throw "RabbitMQ verification failed during cleanup. See $reportPath" }
