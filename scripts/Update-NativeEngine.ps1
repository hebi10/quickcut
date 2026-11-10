[CmdletBinding()]
param(
    [string]$Ref = 'dev/native-engine/windows',
    [long]$RunId = 0,
    [switch]$Dispatch,
    [int]$Timeout = 7200,
    [string]$Gh = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$pythonCommand = if ($env:QUICKCUT_PYTHON) { $env:QUICKCUT_PYTHON } else { 'python' }
$clientArguments = @(
    (Join-Path $PSScriptRoot 'update-native-engine.py'),
    '--repo', $repoRoot, '--ref', $Ref, '--timeout', "$Timeout"
)
if ($RunId -gt 0) { $clientArguments += @('--run-id', "$RunId") }
if ($Dispatch) { $clientArguments += '--dispatch' }
if ($Gh) { $clientArguments += @('--gh', $Gh) }

& $pythonCommand @clientArguments
exit $LASTEXITCODE
