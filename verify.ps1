param([switch]$WindowsTcpPipe)
$ErrorActionPreference = 'Stop'
$bridleRoot = $PSScriptRoot
$bridleArguments = @('-B', '-ntp', '-s', (Join-Path $bridleRoot '.mvn/settings.xml'), ('-Dmaven.repo.local=' + (Join-Path $bridleRoot '.m2-cache')))
if ($WindowsTcpPipe) { $bridleArguments += '-DargLine=-Djdk.net.unixdomain.tmpdir=target/unavailable-unix-domain-directory' }
Push-Location -LiteralPath $bridleRoot
try {
    & mvn @bridleArguments clean verify
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & mvn @bridleArguments com.spotify.fmt:fmt-maven-plugin:2.25:check
    exit $LASTEXITCODE
} finally { Pop-Location }
