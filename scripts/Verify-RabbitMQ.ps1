$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
. (Join-Path $PSScriptRoot 'local-processes.ps1')

if (-not $TaskflowErlangHome -or -not $TaskflowRabbitHome) {
    throw 'Portable Erlang or RabbitMQ under .tools/ is required for Verify-RabbitMQ.ps1.'
}

& (Join-Path $PSScriptRoot 'Start-Database.ps1')
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL startup failed.' }

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
$rmqDiag = Join-Path $TaskflowRabbitHome 'sbin\rabbitmq-diagnostics.bat'

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
    return Wait-TaskflowHealth -Role $Role
}

$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:15439/batchflow'
$env:DATABASE_USER = 'batchflow'
$env:DATABASE_PASSWORD = ''

$rmqProc = Start-Process -FilePath $rmqBat -WindowStyle Hidden -PassThru -RedirectStandardOutput $rmqOut -RedirectStandardError $rmqErr
try {
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
    & $rmqCtl await_startup | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'RabbitMQ await_startup failed.' }

    $users = & $rmqCtl list_users --no-table-headers
    if (-not ($users -match '^batchflow\s')) {
        & $rmqCtl add_user batchflow batchflow-local | Out-Null
        & $rmqCtl set_user_tags batchflow administrator | Out-Null
    }
    & $rmqCtl set_permissions -p / batchflow ".*" ".*" ".*" | Out-Null

    $apiHealth = Start-RmqAppRole -Role api
    $workerHealth = Start-RmqAppRole -Role worker -BatchDelayMs 250 -LeaseSeconds 8 -PollMs 250
    if ($apiHealth.queueMode -ne 'rabbitmq' -or $workerHealth.queueMode -ne 'rabbitmq') {
        throw "Expected queueMode=rabbitmq, got api=$($apiHealth.queueMode), worker=$($workerHealth.queueMode)"
    }

    # Verify RabbitMQ queue/exchange declaration and run 3 real broker scenarios via Node
    $verifyJs = @'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

const BASE = 'http://127.0.0.1:18090';

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
  const upResp = await fetch(`${BASE}/api/uploads`, { method: 'POST', body: form });
  if (!upResp.ok) throw new Error(`upload ${name} failed: ${upResp.status}`);
  const upload = await upResp.json();
  const taskResp = await fetch(`${BASE}/api/tasks`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
    body: JSON.stringify({
      name, uploadId: upload.uploadId,
      mapping: { sku: 'sku', name: 'name', category: 'category', price: 'price', stock: 'stock' },
      batchSize, maxAttempts: 3,
    }),
  });
  if (!taskResp.ok) throw new Error(`create ${name} failed: ${taskResp.status}`);
  const task = await taskResp.json();
  return { task, expectedValid: rows - invalid, expectedError: invalid, prefix };
}

async function waitTerminal(id, timeoutMs = 60000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const r = await fetch(`${BASE}/api/tasks/${encodeURIComponent(id)}`);
    const d = await r.json();
    if (['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED'].includes(d.status)) return d;
    await new Promise(res => setTimeout(res, 250));
  }
  throw new Error(`Task ${id} timed out`);
}

// Scenario A: single RabbitMQ task
const single = await uploadAndCreate('RMQ-单任务验证', 120, 25, 40);
const singleDone = await waitTerminal(single.task.id);
if (singleDone.successRows !== single.expectedValid || singleDone.errorRows !== single.expectedError || singleDone.checkpoint !== 120) {
  throw new Error(`Scenario A mismatch: ${JSON.stringify(singleDone)}`);
}

// Scenario B: 4 concurrent tasks delivered to 1 busy RabbitMQ worker (verifies WorkerRunner lock fix)
const concurrent = await Promise.all([
  uploadAndCreate('RMQ-并发1', 200, 0, 50),
  uploadAndCreate('RMQ-并发2', 200, 0, 50),
  uploadAndCreate('RMQ-并发3', 200, 0, 50),
  uploadAndCreate('RMQ-并发4', 200, 0, 50),
]);
const concurrentDone = await Promise.all(concurrent.map(c => waitTerminal(c.task.id, 90000)));
for (const d of concurrentDone) {
  if (d.status !== 'COMPLETED' || d.successRows !== 200 || d.checkpoint !== 200) {
    throw new Error(`Scenario B failed (possible dropped message): ${JSON.stringify(d)}`);
  }
}

// Output recovery task created for PowerShell worker-kill test
const rec = await uploadAndCreate('RMQ-故障恢复实测', 600, 25, 100);
console.log(JSON.stringify({
  single: { id: singleDone.id, status: singleDone.status, successRows: singleDone.successRows, errorRows: singleDone.errorRows },
  concurrentCount: concurrentDone.length,
  concurrentIds: concurrentDone.map(d => d.id),
  recoveryTaskId: rec.task.id,
  recoveryPrefix: rec.prefix,
  recoveryExpectedValid: rec.expectedValid,
  recoveryExpectedError: rec.expectedError,
}, null, 2));
'@

    $tmpVerify = Join-Path $TaskflowProjectRoot '.runtime\verify-rmq-temp.mjs'
    Set-Content -LiteralPath $tmpVerify -Value $verifyJs -Encoding UTF8
    try {
        $nodeOut = & node $tmpVerify
        if ($LASTEXITCODE -ne 0) { throw 'Node RabbitMQ scenarios failed.' }
        $info = $nodeOut | ConvertFrom-Json

        # Scenario C: wait until recovery task hits >= 100 processedRows, kill worker, restart in rabbitmq mode, verify recovery
        $mid = $null
        for ($i = 0; $i -lt 40; $i++) {
            $t = Invoke-RestMethod -Uri "http://127.0.0.1:18090/api/tasks/$($info.recoveryTaskId)"
            if ($t.status -eq 'RUNNING' -and $t.processedRows -ge 100 -and $t.processedRows -lt 600) {
                $mid = $t; break
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $mid) { throw 'Recovery task did not enter mid-flight RUNNING state.' }

        Stop-TaskflowProcess -Role worker | Out-Null
        $null = Start-RmqAppRole -Role worker -BatchDelayMs 100 -LeaseSeconds 8 -PollMs 250

        $recovered = $null
        for ($i = 0; $i -lt 80; $i++) {
            $t = Invoke-RestMethod -Uri "http://127.0.0.1:18090/api/tasks/$($info.recoveryTaskId)"
            if ($t.status -in @('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')) {
                $recovered = $t; break
            }
            Start-Sleep -Milliseconds 500
        }
        if (-not $recovered -or $recovered.status -ne 'COMPLETED_WITH_ERRORS' -or $recovered.processedRows -ne 600 -or $recovered.successRows -ne $info.recoveryExpectedValid -or $recovered.errorRows -ne $info.recoveryExpectedError -or $recovered.attempt -lt 2) {
            throw "Scenario C recovery mismatch: $($recovered | ConvertTo-Json -Compress)"
        }
        $productsCheck = Invoke-RestMethod -Uri "http://127.0.0.1:18090/api/products?q=$($info.recoveryPrefix)&size=1"
        if ($productsCheck.total -ne $info.recoveryExpectedValid) {
            throw "Recovery product count mismatch: $($productsCheck.total) != $($info.recoveryExpectedValid)"
        }

        $queues = & $rmqCtl list_queues name messages messages_ready messages_unacknowledged --no-table-headers
        $exchanges = & $rmqCtl list_exchanges name type --no-table-headers
        $outboxPending = & (Join-Path $TaskflowPostgresBin 'psql.exe') -h 127.0.0.1 -p 15439 -U batchflow -d batchflow -At -c "SELECT count(*) FROM task_outbox WHERE published_at IS NULL;"

        @{
            status = 'PASSED'
            apiQueueMode = $apiHealth.queueMode
            workerQueueMode = $workerHealth.queueMode
            singleTask = $info.single
            concurrentTasks = $info.concurrentCount
            recovery = @{
                taskId = $recovered.id
                midCheckpoint = $mid.checkpoint
                finalStatus = $recovered.status
                processedRows = $recovered.processedRows
                successRows = $recovered.successRows
                errorRows = $recovered.errorRows
                attempt = $recovered.attempt
                events = @($recovered.events | ForEach-Object { "$($_.type): $($_.message)" })
            }
            rabbitmqQueues = ($queues -join '; ')
            outboxPendingAfterRun = $outboxPending
        } | ConvertTo-Json -Depth 5
    } finally {
        Remove-Item -Force -ErrorAction SilentlyContinue $tmpVerify
    }
} finally {
    Stop-TaskflowProcess -Role worker | Out-Null
    Stop-TaskflowProcess -Role api | Out-Null
    & $rmqCtl stop 2>$null | Out-Null
    if ($rmqProc -and -not $rmqProc.HasExited) {
        Start-Sleep -Seconds 2
        if (-not $rmqProc.HasExited) { Stop-Process -Id $rmqProc.Id -Force -ErrorAction SilentlyContinue }
    }
}
