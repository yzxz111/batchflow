param([ValidateRange(0, 5000)][int]$BatchDelayMs = 200)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
. (Join-Path $PSScriptRoot 'local-processes.ps1')
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:15439/batchflow'
$env:DATABASE_USER = 'batchflow'
$env:DATABASE_PASSWORD = ''
Stop-TaskflowProcess -Role worker
Start-TaskflowProcess -Role worker -BatchDelayMs $BatchDelayMs
$health = Wait-TaskflowHealth -Role worker
Write-Output "Worker restarted, health=$($health.status). In-flight tasks are recovered after their previous lease expires."
