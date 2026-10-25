# Dot-source this script: . .\scripts\Use-LocalEnvironment.ps1
$ErrorActionPreference = 'Stop'
$bankRoot = Split-Path -Parent $PSScriptRoot
$bankEnvPath = Join-Path $bankRoot '.env'
if (-not (Test-Path -LiteralPath $bankEnvPath)) {
    throw 'Create .env from .env.example and replace POSTGRES_PASSWORD first.'
}

$bankValues = @{}
$bankAllowedKeys = @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'POSTGRES_PORT')
foreach ($bankLine in [System.IO.File]::ReadAllLines($bankEnvPath)) {
    if ([string]::IsNullOrWhiteSpace($bankLine) -or $bankLine.TrimStart().StartsWith('#')) { continue }
    $bankPair = $bankLine.Split('=', 2)
    if ($bankPair.Count -ne 2 -or $bankPair[0] -notin $bankAllowedKeys) {
        throw 'Invalid .env entry. Use only the four POSTGRES_* keys in .env.example.'
    }
    $bankValues[$bankPair[0]] = $bankPair[1]
}
foreach ($bankKey in $bankAllowedKeys) {
    if (-not $bankValues.ContainsKey($bankKey) -or [string]::IsNullOrWhiteSpace($bankValues[$bankKey])) {
        throw "Missing $bankKey in .env."
    }
}
if ($bankValues['POSTGRES_PASSWORD'] -eq 'replace-with-a-local-password') {
    throw 'Replace the example password in .env.'
}
if ($bankValues['POSTGRES_DB'] -notmatch '^[a-zA-Z][a-zA-Z0-9_]*$' -or
    $bankValues['POSTGRES_USER'] -notmatch '^[a-zA-Z][a-zA-Z0-9_]*$') {
    throw 'Database/user names must start with a letter and contain only letters, numbers or underscores.'
}
$bankPort = 0
if (-not [int]::TryParse($bankValues['POSTGRES_PORT'], [ref]$bankPort) -or $bankPort -lt 1 -or $bankPort -gt 65535) {
    throw 'POSTGRES_PORT must be between 1 and 65535.'
}
foreach ($bankKey in $bankAllowedKeys) {
    [Environment]::SetEnvironmentVariable($bankKey, $bankValues[$bankKey], 'Process')
}
$env:DB_URL = 'jdbc:postgresql://localhost:{0}/{1}' -f $bankPort, $bankValues['POSTGRES_DB']
$env:DB_USERNAME = $bankValues['POSTGRES_USER']
$env:DB_PASSWORD = $bankValues['POSTGRES_PASSWORD']
$env:SPRING_PROFILES_ACTIVE = 'local'
Write-Host 'Local simulation environment loaded. Credentials are not printed.'
Remove-Variable bankValues, bankPair, bankLine -ErrorAction SilentlyContinue
