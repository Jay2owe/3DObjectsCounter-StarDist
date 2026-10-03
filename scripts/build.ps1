param([string]$RuntimeCore,[switch]$SkipTests)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if(-not $RuntimeCore){$RuntimeCore=Join-Path $root 'runtime-core'}
$RuntimeCore=(Resolve-Path -LiteralPath $RuntimeCore).Path
Push-Location $root
try {
    & .\mvnw.cmd -B -ntp -f (Join-Path $RuntimeCore 'pom.xml') install
    if($LASTEXITCODE -ne 0){throw 'Shared runtime build failed.'}
    & .\mvnw.cmd -B -ntp -f stardist3d-worker/pom.xml install
    if($LASTEXITCODE -ne 0){throw 'Python worker build failed.'}
    $arguments=@('-B','-ntp','verify');if($SkipTests){$arguments+='-DskipTests'}
    & .\mvnw.cmd @arguments
    if($LASTEXITCODE -ne 0){throw 'Counter verification failed.'}
} finally {Pop-Location}
