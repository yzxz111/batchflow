param([switch]$StopDatabase)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
. (Join-Path $PSScriptRoot 'local-processes.ps1')
Stop-TaskflowProcess -Role worker
Stop-TaskflowProcess -Role api
if ($StopDatabase) { & (Join-Path $PSScriptRoot 'Stop-Database.ps1') }
else { Write-Output 'PostgreSQL remains running. Use -StopDatabase to stop it as well.' }
