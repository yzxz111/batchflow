$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

Write-Output "Project: $TaskflowProjectRoot"
Write-Output "Java: $TaskflowJava"
& $TaskflowJava -version
if ($LASTEXITCODE -ne 0) { throw 'Java version check failed.' }
Write-Output "Maven: $TaskflowMaven"
& $TaskflowMaven -version
if ($LASTEXITCODE -ne 0) { throw 'Maven version check failed.' }

$nodeCommand = Get-Command node.exe -ErrorAction SilentlyContinue
$pnpmCommand = Get-Command pnpm.cmd -ErrorAction SilentlyContinue
if ($nodeCommand) { & $nodeCommand.Source --version } else { Write-Warning 'Node.js was not found.' }
if ($pnpmCommand) { & $pnpmCommand.Source --version } else { Write-Warning 'pnpm was not found.' }
if ($TaskflowPostgresBin) { & (Join-Path $TaskflowPostgresBin 'pg_ctl.exe') --version } else { Write-Output 'Native PostgreSQL: not found (optional for the local profile).' }
$gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
if ($gitCommand) { & $gitCommand.Source --version } else { Write-Output 'Git: not found.' }
if ($TaskflowRabbitHome) { Write-Output "RabbitMQ: $TaskflowRabbitHome (ERLANG_HOME=$TaskflowErlangHome)" } else { Write-Output 'RabbitMQ: not found (optional).' }
$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
if ($dockerCommand) { & $dockerCommand.Source --version } else { Write-Output 'Docker: not found (Compose verification needs Docker Desktop).' }
