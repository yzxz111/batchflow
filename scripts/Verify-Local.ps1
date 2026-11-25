param(
    [string]$ReportDirectory,
    [string]$PythonExecutable,
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
. (Join-Path $PSScriptRoot 'local-processes.ps1')

if (-not $ReportDirectory) {
    $runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $ReportDirectory = Join-Path $TaskflowProjectRoot ".runtime\evidence\local\$runId"
}
$ReportDirectory = [IO.Path]::GetFullPath($ReportDirectory)
if (Test-Path -LiteralPath $ReportDirectory) { throw 'ReportDirectory must be new; existing evidence will not be overwritten.' }
if ((Get-TaskflowOwnedProcess -Role api) -or (Get-TaskflowOwnedProcess -Role worker)) {
    throw 'Stop this project API/Worker before running a complete verification. No running process was changed.'
}
if (-not $PythonExecutable) {
    $launcher = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($launcher) { $PythonExecutable = (& $launcher.Source -3.12 -c 'import sys; print(sys.executable)').Trim() }
    else { $PythonExecutable = (Get-Command python.exe -ErrorAction Stop).Source }
}
if (-not (Test-Path -LiteralPath $PythonExecutable)) { throw 'Python executable not found. Set -PythonExecutable.' }
New-Item -ItemType Directory -Path $ReportDirectory | Out-Null
$steps = New-Object System.Collections.Generic.List[object]
function Get-VerificationSourceSnapshot {
    $files = @(@('backend\src', 'web\src', 'scripts', 'deploy', '.github') | ForEach-Object {
        Get-ChildItem -LiteralPath (Join-Path $TaskflowProjectRoot $_) -Recurse -File
    }) + @(@('backend\pom.xml', 'web\package.json', 'web\pnpm-lock.yaml', 'web\pnpm-workspace.yaml', 'web\tsconfig.json', 'web\vite.config.ts', 'web\index.html') | ForEach-Object {
        Get-Item -LiteralPath (Join-Path $TaskflowProjectRoot $_)
    })
    return @($files | Where-Object { $_.FullName -notmatch '[\\/]__pycache__[\\/]' } | Sort-Object FullName | ForEach-Object {
        [ordered]@{ path = $_.FullName.Substring($TaskflowProjectRoot.Length + 1).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    })
}
$sourceSnapshot = @(Get-VerificationSourceSnapshot)
$sourceSnapshot | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $ReportDirectory 'source-files.json') -Encoding UTF8
$report = [ordered]@{
    schemaVersion = 1; status = 'RUNNING'; startedAt = [DateTime]::UtcNow.ToString('o')
    finishedAt = $null; sourceRevision = (& git -C $TaskflowProjectRoot rev-parse HEAD).Trim()
    sourceHasChanges = [bool](& git -C $TaskflowProjectRoot status --porcelain)
    scope = 'Local Windows database and real RabbitMQ profiles; excludes Docker execution and remote CI.'
    sourceSnapshot = 'source-files.json'; steps = $steps; error = $null; cleanup = $null
}
$ownedStarted = $false

function Save-VerificationReport {
    $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $ReportDirectory 'report.json') -Encoding UTF8
}
function Invoke-VerificationStep {
    param([string]$Name, [scriptblock]$Action)
    $step = [ordered]@{ name = $Name; status = 'RUNNING'; startedAt = [DateTime]::UtcNow.ToString('o'); finishedAt = $null; error = $null }
    $steps.Add($step)
    Save-VerificationReport
    try {
        & $Action *>&1 | Tee-Object -FilePath (Join-Path $ReportDirectory "$Name.log")
        $step.status = 'PASSED'
    } catch {
        $step.status = 'FAILED'; $step.error = $_.Exception.Message
        throw
    } finally {
        $step.finishedAt = [DateTime]::UtcNow.ToString('o')
        Save-VerificationReport
    }
}
function Assert-NativeExit {
    param([string]$Description)
    if ($LASTEXITCODE -ne 0) { throw "$Description exited with code $LASTEXITCODE." }
}

Push-Location $TaskflowProjectRoot
try {
    Invoke-VerificationStep 'environment' {
        & $PythonExecutable -c 'import sys,playwright; print(sys.version); print("Playwright import OK")'
        Assert-NativeExit 'Python/Playwright preflight'
        & $TaskflowJava -version
        Assert-NativeExit 'Java version'
        & node --version
        Assert-NativeExit 'Node version'
        Push-Location (Join-Path $TaskflowProjectRoot 'web')
        try { & pnpm.cmd --version; Assert-NativeExit 'pnpm version' } finally { Pop-Location }
        & (Join-Path $PSScriptRoot 'Start-Database.ps1')
        Assert-NativeExit 'PostgreSQL startup'
    }
    if (-not $SkipInstall) {
        Invoke-VerificationStep 'frontend-install' {
            Push-Location (Join-Path $TaskflowProjectRoot 'web')
            try { & pnpm.cmd install --frozen-lockfile; Assert-NativeExit 'Frontend dependency installation' } finally { Pop-Location }
        }
    }
    Invoke-VerificationStep 'frontend-test' {
        Push-Location (Join-Path $TaskflowProjectRoot 'web')
        try {
            & pnpm.cmd exec vitest run --reporter=default --reporter=json "--outputFile=$(Join-Path $ReportDirectory 'frontend-vitest.json')"
            Assert-NativeExit 'Frontend tests'
        } finally { Pop-Location }
    }
    Invoke-VerificationStep 'frontend-build' {
        Push-Location (Join-Path $TaskflowProjectRoot 'web')
        try { & pnpm.cmd build; Assert-NativeExit 'Frontend type check/build' } finally { Pop-Location }
    }
    Invoke-VerificationStep 'backend-verify' {
        $target = Join-Path $ReportDirectory 'backend-tests'
        New-Item -ItemType Directory -Path $target | Out-Null
        try {
            & $TaskflowMaven -B -f backend/pom.xml verify
            Assert-NativeExit 'Backend tests/package'
        } finally {
            $surefire = Join-Path $TaskflowProjectRoot 'backend\target\surefire-reports'
            if (Test-Path -LiteralPath $surefire) {
                Get-ChildItem -LiteralPath $surefire -File | Where-Object { $_.Extension -in '.xml', '.txt' } | Copy-Item -Destination $target
            }
        }
        $jar = Get-Item -LiteralPath (Join-Path $TaskflowProjectRoot 'backend\target\batchflow-0.1.0.jar')
        [ordered]@{ file = $jar.Name; sizeBytes = $jar.Length; sha256 = (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash } |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ReportDirectory 'application-build.json') -Encoding UTF8
    }
    Invoke-VerificationStep 'database-profile-start' {
        $script:ownedStarted = $true
        & (Join-Path $PSScriptRoot 'Start-Local.ps1') -SkipBuild -BatchDelayMs 400
        Assert-NativeExit 'Local API/Worker startup'
    }
    Invoke-VerificationStep 'browser-e2e' {
        & $PythonExecutable (Join-Path $PSScriptRoot 'e2e-browser.py') --report-dir (Join-Path $ReportDirectory 'e2e')
        Assert-NativeExit 'Browser E2E'
    }
    Invoke-VerificationStep 'benchmark' {
        & (Join-Path $PSScriptRoot 'Restart-Worker.ps1') -BatchDelayMs 0
        Assert-NativeExit 'Benchmark Worker startup'
        & node (Join-Path $PSScriptRoot 'benchmark.mjs') --report-dir (Join-Path $ReportDirectory 'benchmark')
        Assert-NativeExit 'Benchmark'
    }
    Invoke-VerificationStep 'database-profile-stop' {
        & (Join-Path $PSScriptRoot 'Stop-Local.ps1')
        $script:ownedStarted = $false
        $target = Join-Path $ReportDirectory 'database-process-logs'
        New-Item -ItemType Directory -Path $target | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $TaskflowProjectRoot '.runtime\logs') -File |
            Where-Object { $_.Name -match '^(api|worker)\.(out|err)\.log$' } | Copy-Item -Destination $target
    }
    Invoke-VerificationStep 'rabbitmq-profile' {
        try {
            & (Join-Path $PSScriptRoot 'Verify-RabbitMQ.ps1') -ReportDirectory (Join-Path $ReportDirectory 'rabbitmq')
        } finally {
            $target = Join-Path $ReportDirectory 'rabbitmq-process-logs'
            New-Item -ItemType Directory -Path $target -Force | Out-Null
            Get-ChildItem -LiteralPath (Join-Path $TaskflowProjectRoot '.runtime\logs') -File |
                Where-Object { $_.Name -match '^(api|worker|rabbitmq)\.(out|err)\.log$' } | Copy-Item -Destination $target
            $brokerLog = Join-Path $TaskflowProjectRoot '.runtime\rabbitmq\log\rabbit@localhost.log'
            if (Test-Path -LiteralPath $brokerLog) { Copy-Item -LiteralPath $brokerLog -Destination (Join-Path $target 'broker.log') }
        }
    }
    Invoke-VerificationStep 'source-integrity' {
        $after = @(Get-VerificationSourceSnapshot)
        $beforeJson = ConvertTo-Json -InputObject $sourceSnapshot -Depth 5 -Compress
        $afterJson = ConvertTo-Json -InputObject $after -Depth 5 -Compress
        if ($beforeJson -cne $afterJson) { throw 'Source/config files changed during verification. Run again on a stable source snapshot.' }
        Write-Output 'Source and configuration SHA-256 hashes remained unchanged throughout verification.'
    }
    $report.status = 'PASSED'
} catch {
    $report.status = 'FAILED'; $report.error = $_.Exception.Message
    throw
} finally {
    $cleanup = [ordered]@{ roles = @(); database = 'PostgreSQL and existing data retained.' }
    foreach ($role in @('worker', 'api')) {
        try {
            # Includes repair after a child RabbitMQ cleanup failure. Every stop
            # still validates this project's PID, role, jar and creation time.
            Stop-TaskflowProcess -Role $role
            $remaining = Get-TaskflowOwnedProcess -Role $role
            if ($remaining) { throw "$role is still running after cleanup." }
            $cleanup.roles += @{ role = $role; status = 'STOPPED' }
        } catch {
            $cleanup.roles += @{ role = $role; status = 'FAILED'; error = $_.Exception.Message }
            $report.status = 'FAILED'
        }
    }
    $report.cleanup = $cleanup
    $report.finishedAt = [DateTime]::UtcNow.ToString('o')
    Save-VerificationReport
    Pop-Location
    Write-Output "Verification report: $(Join-Path $ReportDirectory 'report.json')"
}
if ($report.status -ne 'PASSED') { throw 'Verification did not complete successfully. See report.json.' }
