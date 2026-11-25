# Dot-source this file to configure Java, Maven, Node, pnpm and PostgreSQL for this process only.
# No machine/user environment variables are changed.
$TaskflowProjectRoot = Split-Path -Parent $PSScriptRoot

function Find-TaskflowHome {
    param(
        [string[]]$Candidates,
        [string]$Executable
    )
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate $Executable))) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

$taskflowToolRoot = Join-Path $TaskflowProjectRoot '.tools'
$taskflowSiblingTools = Join-Path (Split-Path -Parent $TaskflowProjectRoot) 'yijia-local-runtime\tools'
$taskflowJavaCandidates = @($env:TASKFLOW_JAVA_HOME, $env:JAVA_HOME)
$taskflowMavenCandidates = @($env:TASKFLOW_MAVEN_HOME, $env:MAVEN_HOME, $env:M2_HOME)
$taskflowNodeCandidates = @($env:TASKFLOW_NODE_HOME, (Join-Path $taskflowToolRoot 'node'), (Join-Path $taskflowSiblingTools 'node'))

foreach ($searchRoot in @($taskflowToolRoot, $taskflowSiblingTools)) {
    if (Test-Path -LiteralPath $searchRoot) {
        $taskflowJavaCandidates += @(Get-ChildItem -LiteralPath $searchRoot -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue | ForEach-Object FullName)
        $taskflowMavenCandidates += @(Get-ChildItem -LiteralPath $searchRoot -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue | ForEach-Object FullName)
    }
}

$taskflowJavaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
if ($taskflowJavaCommand) {
    $taskflowJavaCandidates += Split-Path -Parent (Split-Path -Parent $taskflowJavaCommand.Source)
}
$taskflowMavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($taskflowMavenCommand) {
    $taskflowMavenCandidates += Split-Path -Parent (Split-Path -Parent $taskflowMavenCommand.Source)
}
$taskflowNodeCommand = Get-Command node.exe -ErrorAction SilentlyContinue
if ($taskflowNodeCommand) {
    $taskflowNodeCandidates += Split-Path -Parent $taskflowNodeCommand.Source
}

$TaskflowJavaHome = Find-TaskflowHome -Candidates $taskflowJavaCandidates -Executable 'bin\java.exe'
$TaskflowMavenHome = Find-TaskflowHome -Candidates $taskflowMavenCandidates -Executable 'bin\mvn.cmd'
$TaskflowNodeHome = Find-TaskflowHome -Candidates $taskflowNodeCandidates -Executable 'node.exe'
$taskflowBinDir = Join-Path $taskflowToolRoot 'bin'
if (-not $TaskflowJavaHome) {
    throw 'JDK 21 was not found. Set TASKFLOW_JAVA_HOME or extract a JDK 21 under .tools\jdk-21.'
}
if (-not $TaskflowMavenHome) {
    throw 'Maven was not found. Set TASKFLOW_MAVEN_HOME or extract Maven 3.9+ under .tools\apache-maven-*.'
}

$env:JAVA_HOME = $TaskflowJavaHome
$env:MAVEN_HOME = $TaskflowMavenHome
$env:COREPACK_ENABLE_DOWNLOAD_PROMPT = '0'
$pathEntries = @((Join-Path $TaskflowJavaHome 'bin'), (Join-Path $TaskflowMavenHome 'bin'))
if (Test-Path -LiteralPath $taskflowBinDir) { $pathEntries += (Resolve-Path -LiteralPath $taskflowBinDir).Path }
if ($TaskflowNodeHome) { $pathEntries += $TaskflowNodeHome }
$env:PATH = ($pathEntries -join ';') + ';' + $env:PATH
$TaskflowJava = Join-Path $TaskflowJavaHome 'bin\java.exe'
$TaskflowMaven = Join-Path $TaskflowMavenHome 'bin\mvn.cmd'

# Native PostgreSQL binaries are optional; they never share the sibling project's data.
$taskflowPostgresCommand = Get-Command pg_ctl.exe -ErrorAction SilentlyContinue
$TaskflowPostgresBin = $null
foreach ($candidate in @($env:TASKFLOW_POSTGRES_BIN, (Join-Path $taskflowToolRoot 'pgsql\bin'), (Join-Path $taskflowSiblingTools 'pgsql\bin'))) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'pg_ctl.exe'))) {
        $TaskflowPostgresBin = (Resolve-Path -LiteralPath $candidate).Path
        break
    }
}
if (-not $TaskflowPostgresBin -and $taskflowPostgresCommand) {
    $TaskflowPostgresBin = Split-Path -Parent $taskflowPostgresCommand.Source
}

# Portable Git, Erlang and RabbitMQ under .tools/ are optional for local verification.
$TaskflowGitBin = $null
foreach ($candidate in @($env:TASKFLOW_GIT_BIN, (Join-Path $taskflowToolRoot 'git\cmd'))) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'git.exe'))) {
        $TaskflowGitBin = (Resolve-Path -LiteralPath $candidate).Path
        $env:PATH = "$TaskflowGitBin;$env:PATH"
        break
    }
}

$TaskflowErlangHome = $null
foreach ($candidate in @($env:ERLANG_HOME, (Join-Path $taskflowToolRoot 'erlang'))) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\erl.exe'))) {
        $TaskflowErlangHome = (Resolve-Path -LiteralPath $candidate).Path
        $env:ERLANG_HOME = $TaskflowErlangHome
        $env:PATH = "$(Join-Path $TaskflowErlangHome 'bin');$env:PATH"
        break
    }
}

$TaskflowRabbitHome = $null
$rabbitSearch = @($env:RABBITMQ_HOME) + @(Get-ChildItem -LiteralPath (Join-Path $taskflowToolRoot 'rabbitmq') -Directory -Filter 'rabbitmq_server-*' -ErrorAction SilentlyContinue | ForEach-Object FullName)
foreach ($candidate in $rabbitSearch) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'sbin\rabbitmq-server.bat'))) {
        $TaskflowRabbitHome = (Resolve-Path -LiteralPath $candidate).Path
        $env:RABBITMQ_HOME = $TaskflowRabbitHome
        $env:PATH = "$(Join-Path $TaskflowRabbitHome 'sbin');$env:PATH"
        break
    }
}
