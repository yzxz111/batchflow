$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
& $TaskflowMaven @args
exit $LASTEXITCODE
