#Requires -Version 5.1
<#
.SYNOPSIS
  Build SES Manager Pro app image offline and record LOCAL_IMAGE_ID (not ECR digest).
#>
param(
  [string]$ImageTag = 'ses-manager-pro:local',
  [string]$JreImage = 'eclipse-temurin:21-jre-jammy',
  [string]$BaseDigestSha = 'eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf',
  [switch]$SkipMaven
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$LockPath = Join-Path $PSScriptRoot 'image-lock.json'

$javaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
if (Test-Path $javaHome) {
  $env:JAVA_HOME = $javaHome
  $env:PATH = "$javaHome\bin;$RepoRoot\apache-maven-3.9.6\bin;$env:PATH"
}

Push-Location $RepoRoot
try {
  $gitRevision = (git rev-parse HEAD).Trim()

  if (-not $SkipMaven) {
    Write-Host '=== mvn package -DskipTests ==='
    & mvn -B -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed exit=$LASTEXITCODE" }
  }

  $jar = Get-ChildItem target -Filter 'ses-manager-pro-*.jar' |
    Where-Object { $_.Name -notmatch 'original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
  if (-not $jar) { throw 'target JAR not found' }
  $jarSha = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  Write-Host "JAR=$($jar.Name) sha256=$jarSha"

  $basePinned = "${JreImage}@sha256:${BaseDigestSha}"
  Write-Host "=== docker pull $basePinned ==="
  docker pull $basePinned | Out-Host
  if ($LASTEXITCODE -ne 0) { throw 'docker pull base failed' }

  Write-Host '=== docker build ==='
  docker build `
    --build-arg "JRE_BASE=$basePinned" `
    --build-arg "APP_JAR=$($jar.Name)" `
    -t $ImageTag `
    -f Dockerfile `
    .
  if ($LASTEXITCODE -ne 0) { throw 'docker build failed' }

  $imgId = (docker inspect --format '{{.Id}}' $ImageTag).Trim()
  if ($imgId -notmatch '^sha256:[a-f0-9]{64}$') { throw "unexpected local image id: $imgId" }

  docker run --rm --entrypoint java $ImageTag -version | Out-Host

  $lock = [ordered]@{
    schemaVersion = 4
    generated = (Get-Date -Format o)
    freezeBaseline = 'ad829cc2e5c249c4cc273ba7b87441da163a48f3'
    gitRevision = $gitRevision
    imageSourceRevision = $gitRevision
    evidenceRevision = $null
    evidenceRevisionNote = 'Set in evidence pack to final tip SHA; do not rebuild solely to self-reference'
    appJar = $jar.Name
    jarSha256 = $jarSha
    baseImage = $JreImage
    baseImageDigest = "sha256:$BaseDigestSha"
    localImageTag = $ImageTag
    localImageId = $imgId
    registryDigest = $null
    registryDigestStatus = 'ABSENT - LOCAL_IMAGE_ID is not an ECR digest or ECS deployable registry digest'
    ecrPush = 'OUT-OF-SCOPE - no AWS account'
    ecsDeploy = 'OUT-OF-SCOPE - no AWS account'
    efsActual = 'DEFERRED - no AWS account'
    sbomScan = 'BLOCKED-TOOLING'
    p3AptReproducibility = 'OPEN - base apt package versions may drift across rebuild dates'
  }
  $utf8 = New-Object System.Text.UTF8Encoding $false
  [System.IO.File]::WriteAllText($LockPath, ($lock | ConvertTo-Json -Depth 5), $utf8)
  Write-Host "LOCAL_IMAGE_ID=$imgId"
  Write-Host "Wrote $LockPath"
} finally {
  Pop-Location
}
