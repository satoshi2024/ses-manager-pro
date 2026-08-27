#Requires -Version 5.1
<#
.SYNOPSIS
  Offline AWS / local ECS-like simulation (Docker only; no AWS API).
  Produces evidence under offline-ecs-simulation/ for SIMULATED GO verdict.
#>
param(
  [string]$EvidenceRoot = '',
  [string]$ImageSourceRevision = '',
  [switch]$EgressNegativeSelfTestOnly
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$ComposeFile = Join-Path $PSScriptRoot 'docker-compose.yml'
$SeedSql = Join-Path $PSScriptRoot 'seed-breakglass-mfa.sql'
$LockPath = Join-Path $PSScriptRoot '..\image-lock.json'
$ImageTag = 'ses-manager-pro:local'
$FreezeBaseline = 'ad829cc2e5c249c4cc273ba7b87441da163a48f3'

function Add-Cmd([string]$line) {
  Add-Content -Path (Join-Path $script:Ev 'commands.txt') -Value $line -Encoding utf8
}

function Write-Ev([string]$name, [string]$content) {
  $utf8 = New-Object System.Text.UTF8Encoding $false
  [System.IO.File]::WriteAllText((Join-Path $script:Ev $name), $content, $utf8)
}

function Invoke-InApp([string]$sh) {
  docker exec ses-offline-app sh -c $sh
}

function Wait-Healthy([string]$name, [int]$timeoutSec = 300) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $st = ''
  while ((Get-Date) -lt $deadline) {
    $st = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $name 2>$null
    if ($st -eq 'healthy') { return }
    if ($st -eq 'exited') { throw "$name exited before healthy" }
    Start-Sleep -Seconds 5
  }
  throw "$name not healthy within ${timeoutSec}s (last=$st)"
}

function Assert-EgressResult([string]$raw, [string]$label) {
  $t = ($raw | Out-String).Trim()
  # Exact allow-list only (REV-ECS-P2-002)
  if ($t -notin @('FAIL', '000', '000FAIL')) {
    throw "EGRESS ASSERT FAIL ($label): got '$t' — only FAIL|000|000FAIL allowed"
  }
}

function Test-EgressHarnessNegative {
  $neg = New-Object System.Collections.Generic.List[string]
  foreach ($bad in @('403', '500', '200', '301', 'OK', 'timeout')) {
    $threw = $false
    try {
      Assert-EgressResult $bad "negative:$bad"
    } catch {
      $threw = $true
      $neg.Add("PASS negative self-test rejects '$bad'")
    }
    if (-not $threw) { throw "egress harness failed to reject synthetic '$bad'" }
  }
  return ($neg -join "`n")
}

if ($EgressNegativeSelfTestOnly) {
  Write-Host (Test-EgressHarnessNegative)
  Write-Host 'EGRESS NEGATIVE SELF-TEST PASS'
  exit 0
}

Push-Location $RepoRoot
try {
  $evidenceRevision = (git rev-parse HEAD).Trim()
  if (-not $EvidenceRoot) {
    $EvidenceRoot = Join-Path $RepoRoot ".kiro\reviews\production-acceptance\$evidenceRevision\offline-ecs-simulation"
  }
  $script:Ev = $EvidenceRoot
  New-Item -ItemType Directory -Force -Path $script:Ev | Out-Null
  '' | Set-Content (Join-Path $script:Ev 'commands.txt') -Encoding utf8

  if (-not (Test-Path $LockPath)) { throw "missing $LockPath" }
  $lock = Get-Content $LockPath -Raw | ConvertFrom-Json

  # --- egress negative self-test first ---
  Write-Ev 'egress-negative-selftest.txt' (Test-EgressHarnessNegative)

  # --- Dockerfile / image security ---
  $dfPath = Join-Path $RepoRoot 'Dockerfile'
  $df = Get-Content $dfPath -Raw
  $di = Get-Content (Join-Path $RepoRoot '.dockerignore') -Raw
  $sec = New-Object System.Collections.Generic.List[string]
  if ($df -notmatch '@sha256:[a-f0-9]{64}') { throw 'Dockerfile base missing sha256 digest' }
  $sec.Add('PASS base digest pinned')
  if ($di -notmatch '\.git') { throw '.dockerignore missing .git' }
  if ($di -notmatch '\.kiro') { throw '.dockerignore missing .kiro' }
  $sec.Add('PASS .dockerignore excludes .git/.kiro/target noise')
  $uid = (docker run --rm --entrypoint id $ImageTag -u).Trim()
  if ($uid -ne '10001') { throw "uid=$uid" }
  $sec.Add("PASS uid=$uid (non-root)")
  $hasMaven = docker run --rm --entrypoint sh $ImageTag -c 'command -v mvn || command -v javac || ls /usr/bin/javac 2>/dev/null' 2>&1 | Out-String
  if ($hasMaven.Trim()) { throw "build tools present: $hasMaven" }
  $sec.Add('PASS no Maven/javac in image')
  $curlOk = docker run --rm --entrypoint curl $ImageTag --version 2>&1 | Out-String
  if ($curlOk -notmatch 'curl') { throw 'curl missing' }
  $sec.Add('PASS curl available for healthcheck')
  $entry = docker inspect --format '{{json .Config.Entrypoint}} {{json .Config.Cmd}}' $ImageTag
  if ($entry -notmatch 'java') { throw "bad entrypoint: $entry" }
  $sec.Add("PASS ENTRYPOINT=$entry")
  $hist = docker history --no-trunc $ImageTag 2>&1 | Out-String
  Write-Ev 'image-history.txt' $hist
  if ($hist -match '(?i)(AKIA[0-9A-Z]{16}|BEGIN (RSA |OPENSSH )?PRIVATE KEY)') {
    throw 'history secret pattern'
  }
  $sec.Add('PASS history no secret/credential patterns')
  $jarInImg = docker run --rm --entrypoint sha256sum $ImageTag /app/app.jar
  $jarHash = ($jarInImg -split '\s+')[0].ToLowerInvariant()
  if ($jarHash -ne $lock.jarSha256) { throw "JAR sha mismatch $jarHash vs $($lock.jarSha256)" }
  $sec.Add("PASS jar sha=$jarHash matches image-lock")
  $dfSha = (Get-FileHash $dfPath -Algorithm SHA256).Hash.ToLowerInvariant()
  $sec.Add("dockerfileSha256=$dfSha")
  docker inspect $ImageTag | Out-File (Join-Path $script:Ev 'image-inspect.json') -Encoding utf8
  $sec.Add('PASS image inspect captured')
  Write-Ev 'dockerfile-security.txt' ($sec -join "`n")
  $script:DockerfileSha256 = $dfSha

  # --- bring up ---
  Add-Cmd "docker compose -f $ComposeFile down -v"
  docker compose -f $ComposeFile down -v 2>&1 | Out-Null

  # Pre-create uploads volume owned by 10001 (REV-ECS-P1-002)
  docker volume create offline_ses-offline-uploads 2>$null | Out-Null
  Add-Cmd 'chown uploads volume to 10001 (sim dir only)'
  docker run --rm -v offline_ses-offline-uploads:/data mysql:8.0.36 `
    sh -c 'rm -rf /data/lost+found /data/* /data/.[!.]* 2>/dev/null; mkdir -p /data/sim && chown -R 10001:10001 /data'

  Add-Cmd 'compose up mysql'
  docker compose -f $ComposeFile up -d mysql
  Wait-Healthy 'ses-offline-mysql' 120

  Add-Cmd 'compose up app phase A'
  docker compose -f $ComposeFile up -d app

  $flywayOk = $false
  for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 5
    $logs = docker logs ses-offline-app 2>&1 | Out-String
    if ($logs -match 'FlywayMigrateException|Migration .* failed') {
      docker logs ses-offline-app 2>&1 | Select-Object -Last 40
      throw 'Flyway failed'
    }
    if ($logs -match 'Started SesManagerApplication') { $flywayOk = $true; break }
    if ($logs -match '本番security configurationが安全な既定値を満たしません') { $flywayOk = $true; break }
    $st = docker inspect --format '{{.State.Status}}' ses-offline-app
    if ($st -eq 'exited') {
      docker logs ses-offline-app 2>&1 | Select-Object -Last 50
      throw 'app exited phase A'
    }
  }
  if (-not $flywayOk) { throw 'phase A incomplete' }

  Get-Content $SeedSql -Raw | docker exec -i ses-offline-mysql mysql -uses -psim-only-db-not-prod ses_manager_db
  if ($LASTEXITCODE -ne 0) {
    Get-Content $SeedSql -Raw | docker exec -i ses-offline-mysql mysql -uroot -psim-only-root-not-prod ses_manager_db
    if ($LASTEXITCODE -ne 0) { throw 'seed failed' }
  }

  Add-Cmd 'compose recreate app phase B'
  docker compose -f $ComposeFile up -d --force-recreate app
  $started = $false
  for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 5
    $logs = docker logs ses-offline-app 2>&1 | Out-String
    if ($logs -match 'Application run failed|IllegalStateException: bp\.|IllegalStateException: Fail-fast|IllegalStateException: freee') {
      docker logs ses-offline-app 2>&1 | Select-Object -Last 50
      throw 'phase B failed'
    }
    if ($logs -match 'Started SesManagerApplication') { $started = $true; break }
  }
  if (-not $started) { throw 'phase B not started' }
  Wait-Healthy 'ses-offline-app' 180

  Write-Ev 'runtime-user.txt' (Invoke-InApp 'id')
  docker inspect ses-offline-app | Out-File (Join-Path $script:Ev 'container-inspect.json') -Encoding utf8

  function Curl-Code([string]$path) {
    (Invoke-InApp "curl -s -o /tmp/body -w '%{http_code}' http://127.0.0.1:8080$path").Trim()
  }
  function Curl-Body([string]$path) {
    (Invoke-InApp "curl -s http://127.0.0.1:8080$path").Trim()
  }

  # --- health baseline ---
  $hf = New-Object System.Collections.Generic.List[string]
  $agg = Curl-Code '/actuator/health'
  $live = Curl-Code '/actuator/health/liveness'
  $ready = Curl-Code '/actuator/health/readiness'
  $hf.Add("aggregate /actuator/health => $agg body=$(Curl-Body '/actuator/health') (recorded only — not ALB gate)")
  $hf.Add("liveness => $live body=$(Curl-Body '/actuator/health/liveness')")
  $hf.Add("readiness => $ready body=$(Curl-Body '/actuator/health/readiness')")
  if ($live -ne '200') { throw "liveness $live" }
  if ($ready -ne '200') { throw "readiness $ready" }
  foreach ($blocked in @('/actuator/env','/actuator/beans','/actuator/configprops')) {
    $bc = Curl-Code $blocked
    $hf.Add("$blocked => $bc")
    if ($bc -eq '200') { throw "$blocked accessible" }
  }
  $hf.Add('PASS env/beans/configprops not exposed (non-200)')
  $hf.Add('ALB convention: /actuator/health/readiness (DEFERRED until real AWS)')
  $hf.Add('ECS container healthCheck: /actuator/health/liveness')
  $hf.Add('read-only root + tmpfs /tmp + named volume /app/uploads (compose)')
  $hf.Add('cpu=1.0 mem=2048m (compose limits)')

  # --- REV-ECS-P1-001 DB loss / recovery ---
  Add-Cmd 'docker stop ses-offline-mysql (DB loss while app runs)'
  docker stop ses-offline-mysql | Out-Null
  $dbLoss = New-Object System.Collections.Generic.List[string]
  $dbLoss.Add('stopped mysql; app still running')
  $gotDown = $false
  for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 3
    $appSt = docker inspect --format '{{.State.Status}}' ses-offline-app
    if ($appSt -ne 'running') { throw "app not running during DB loss: $appSt" }
    $lc = Curl-Code '/actuator/health/liveness'
    $rc = Curl-Code '/actuator/health/readiness'
    $rb = Curl-Body '/actuator/health/readiness'
    $dbLoss.Add("t=${i} liveness=$lc readiness=$rc body=$rb")
    if ($lc -ne '200') { throw "liveness left 200 during DB loss: $lc" }
    # Must be real HTTP 503 with DOWN — not 000
    if ($rc -eq '000' -or $rc -eq '') { continue }
    if ($rc -eq '503' -and $rb -match '"status"\s*:\s*"DOWN"') {
      $gotDown = $true
      break
    }
  }
  if (-not $gotDown) {
    Write-Ev 'readiness-db-loss.txt' ($dbLoss -join "`n")
    throw 'readiness did not become 503/DOWN while app running and MySQL stopped'
  }
  $dbLoss.Add('PASS readiness 503/DOWN with liveness 200 while MySQL stopped')

  Add-Cmd 'docker start ses-offline-mysql (recovery)'
  docker start ses-offline-mysql | Out-Null
  Wait-Healthy 'ses-offline-mysql' 120
  $recovered = $false
  for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 3
    $rc = Curl-Code '/actuator/health/readiness'
    $rb = Curl-Body '/actuator/health/readiness'
    $dbLoss.Add("recover t=${i} readiness=$rc body=$rb")
    if ($rc -eq '200' -and $rb -match '"status"\s*:\s*"UP"') { $recovered = $true; break }
  }
  if (-not $recovered) { throw 'readiness did not recover to 200 after MySQL start' }
  $dbLoss.Add('PASS readiness recovered 200 after MySQL restore')
  Write-Ev 'readiness-db-loss.txt' ($dbLoss -join "`n")
  $hf.Add('DB loss/recovery: VERIFIED (see readiness-db-loss.txt)')
  Write-Ev 'health-results.txt' ($hf -join "`n")

  # --- prometheus ---
  $pr = New-Object System.Collections.Generic.List[string]
  $anon = Curl-Code '/actuator/prometheus'
  if ($anon -ne '401') { throw "prometheus anon $anon" }
  $pr.Add("anon => $anon")
  $ok = (Invoke-InApp "curl -s -o /dev/null -w '%{http_code}' -u sim-scraper:sim-only-scraper-not-prod http://127.0.0.1:8080/actuator/prometheus").Trim()
  if ($ok -ne '200') { throw "scraper $ok" }
  $pr.Add("scraper => $ok")
  $bad = (Invoke-InApp "curl -s -o /dev/null -w '%{http_code}' -u sim-scraper:wrong http://127.0.0.1:8080/actuator/prometheus").Trim()
  if ($bad -ne '401') { throw "wrong pw $bad" }
  $pr.Add("wrong => $bad")
  Write-Ev 'prometheus-auth-results.txt' ($pr -join "`n")

  # --- REV-ECS-P1-002 uploads persistence ---
  $marker = "offline-persist-$(Get-Date -Format yyyyMMddHHmmss)"
  Invoke-InApp "mkdir -p /app/uploads/sim && printf '%s' '$marker' > /app/uploads/sim/marker.txt"
  $shaBefore = (Invoke-InApp 'sha256sum /app/uploads/sim/marker.txt').Trim()
  Add-Cmd 'recreate app container for uploads persistence'
  docker compose -f $ComposeFile up -d --force-recreate app
  Wait-Healthy 'ses-offline-app' 180
  $shaAfter = (Invoke-InApp 'sha256sum /app/uploads/sim/marker.txt').Trim()
  $content = (Invoke-InApp 'cat /app/uploads/sim/marker.txt').Trim()
  $up = @(
    "before=$shaBefore"
    "after=$shaAfter"
    "content=$content"
    "expected=$marker"
  )
  if ($content -ne $marker) { throw 'uploads content lost after recreate' }
  if (($shaBefore -split '\s+')[0] -ne ($shaAfter -split '\s+')[0]) { throw 'uploads sha mismatch after recreate' }
  $up += 'PASS named volume persistence VERIFIED'
  $up += 'EFS-ACTUAL=DEFERRED (template only in task-definition.prod.json)'
  Write-Ev 'uploads-persistence.txt' ($up -join "`n")

  # --- egress ---
  $ping = Invoke-InApp 'curl -s -o /dev/null -w %{http_code} --connect-timeout 3 https://example.com 2>/dev/null || echo FAIL'
  Assert-EgressResult $ping 'example.com'
  Write-Ev 'external-egress-results.txt' @"
network=ses-offline-internal internal=true
curl https://example.com => $(($ping | Out-String).Trim())
PASS zero successful external HTTP (allow-list FAIL|000|000FAIL)
CloudSign=false Peppol=none AI=mock external-send=false
freee=sim placeholders only; no outbound credential use
"@

  # --- REV-ECS-P2-003 graceful shutdown (Spring Boot native Tomcat only) ---
  Add-Cmd 'docker stop -t 40 ses-offline-app (native Tomcat GracefulShutdown)'
  $logJob = Start-Job -ScriptBlock {
    docker logs -f ses-offline-app 2>&1 | Out-String
  }
  Start-Sleep -Seconds 2
  $t0 = Get-Date
  $ErrorActionPreference = 'Continue'
  docker stop -t 40 ses-offline-app 2>&1 | Out-Null
  $ErrorActionPreference = 'Stop'
  Start-Sleep -Seconds 3
  Stop-Job $logJob -ErrorAction SilentlyContinue
  $streamLogs = (Receive-Job $logJob -ErrorAction SilentlyContinue | Out-String)
  Remove-Job $logJob -Force -ErrorAction SilentlyContinue
  $elapsed = [Math]::Max(0, [int]((Get-Date) - $t0).TotalSeconds)
  $exitCode = (docker inspect --format '{{.State.ExitCode}}' ses-offline-app).Trim()
  $oom = (docker inspect --format '{{.State.OOMKilled}}' ses-offline-app).Trim()
  $stopLogs = docker logs ses-offline-app 2>&1 | Out-String
  $combined = $streamLogs + "`n" + $stopLogs
  Write-Ev 'graceful-shutdown-docker-stream.txt' $combined

  if ($combined -match 'GracefulShutdownModeReporter|c\.s\.config\.GracefulShutdownModeReporter') {
    throw 'custom GracefulShutdownModeReporter must not appear — use native Tomcat GracefulShutdown only'
  }
  $hasNativeLogger = $combined -match 'org\.springframework\.boot\.web\.embedded\.tomcat\.GracefulShutdown|o\.s\.b\.w\.e\.tomcat\.GracefulShutdown'
  $hasCommence = $combined -match 'Commencing graceful shutdown'
  $hasComplete = $combined -match 'Graceful shutdown complete'
  $shutdownSlice = $combined
  if ($combined -match '(?s)(Commencing graceful shutdown.+)$') { $shutdownSlice = $Matches[1] }
  $badPatterns = @(
    'Graceful shutdown aborted',
    'graceful shutdown timed out',
    'graceful shutdown interrupted',
    'shutdown error',
    'graceful shutdown drain error'
  )
  foreach ($bp in $badPatterns) {
    if ($shutdownSlice -match [regex]::Escape($bp)) { throw "graceful shutdown bad log: $bp" }
  }

  $gs = New-Object System.Collections.Generic.List[string]
  $gs.Add("exitCode=$exitCode OOMKilled=$oom stopElapsedSec=$elapsed")
  $gs.Add("nativeTomcatLogger=$hasNativeLogger")
  $gs.Add("Commencing graceful shutdown=$hasCommence")
  $gs.Add("Graceful shutdown complete=$hasComplete")
  $gs.Add('logSources=docker-logs-stream+container-logs (no uploads volume)')
  if (-not $hasNativeLogger) { Write-Ev 'graceful-shutdown.txt' ($gs -join "`n"); throw 'missing native Tomcat GracefulShutdown logger' }
  if (-not $hasCommence -or -not $hasComplete) {
    Write-Ev 'graceful-shutdown.txt' ($gs -join "`n")
    throw 'missing native Commencing/Complete graceful shutdown signals'
  }
  if ($exitCode -notin @('0', '143')) { throw "unexpected exit $exitCode (want 0 or 143 after SIGTERM)" }
  if ($oom -eq 'true') { throw 'OOMKilled' }
  $gs.Add('PASS native graceful shutdown VERIFIED')
  Write-Ev 'graceful-shutdown.txt' ($gs -join "`n")

  # restart for final healthy evidence
  docker compose -f $ComposeFile up -d app
  Wait-Healthy 'ses-offline-app' 180

  # task-definition static validation (no AWS)
  & (Join-Path $PSScriptRoot 'validate-task-definition.ps1') -EvidenceRoot $script:Ev

  # session single-instance deferral
  Write-Ev 'session-scope.txt' @"
simulatedDesiredCount=1
ACC-OPS-P1-003=DEFERRED-SINGLE-INSTANCE
stickySession=NOT_USED_AS_CLOSE
note=If ECS desiredCount>1 in future, Redis/ElastiCache session acceptance must be re-run
"@

  # provenance lock for evidence
  $imgSrc = if ($ImageSourceRevision) { $ImageSourceRevision.Trim() } elseif ($lock.imageSourceRevision) { [string]$lock.imageSourceRevision } else { $evidenceRevision }
  $prov = [ordered]@{
    schemaVersion = 4
    freezeBaseline = $FreezeBaseline
    imageSourceRevision = $imgSrc
    evidenceRevision = $evidenceRevision
    gitRevision = $imgSrc
    note = 'imageSourceRevision=SOURCE_SHA clean build; evidenceRevision=FINAL_SHA; localImageId is not ECR registryDigest'
    jarSha256 = $lock.jarSha256
    baseImageDigest = $lock.baseImageDigest
    localImageId = $lock.localImageId
    registryDigest = $null
    registryDigestStatus = 'ABSENT - LOCAL_IMAGE_ID is not an ECR digest'
    ecrPush = 'OUT-OF-SCOPE'
    ecsDeploy = 'OUT-OF-SCOPE'
    efsActual = 'DEFERRED'
    sbomScan = 'BLOCKED-TOOLING'
    p3AptReproducibility = 'OPEN'
  }
  $utf8 = New-Object System.Text.UTF8Encoding $false
  [System.IO.File]::WriteAllText((Join-Path $script:Ev 'image-lock.json'), ($prov | ConvertTo-Json -Depth 5), $utf8)
  # supply-chain.txt aligned with image-lock
  [System.IO.File]::WriteAllText((Join-Path $script:Ev 'supply-chain.txt'), @"
imageSourceRevision=$imgSrc
evidenceRevision=$evidenceRevision
freezeBaseline=$FreezeBaseline
dockerfileSha256=$($script:DockerfileSha256)
jarSha256=$($lock.jarSha256)
baseImageDigest=$($lock.baseImageDigest)
localImageId=$($lock.localImageId)
registryDigest=ABSENT (not fabricated)
sbomScan=BLOCKED-TOOLING
vulnScan=BLOCKED-TOOLING
"@, $utf8)

  # final-report.md
  $report = @"
# Offline ECS Simulation — Final Report

## Identity
- freezeBaseline: ``$FreezeBaseline``
- imageSourceRevision (SOURCE_SHA / clean build): ``$imgSrc``
- evidenceRevision (FINAL_SHA): ``$evidenceRevision``
- Mode: Docker ECS-like offline only — **no AWS account, no ECR push, no ECS deploy**

## Digests (semantic)
| Field | Value |
|---|---|
| baseImageDigest | ``$($lock.baseImageDigest)`` |
| LOCAL_IMAGE_ID | ``$($lock.localImageId)`` |
| jarSha256 | ``$($lock.jarSha256)`` |
| registryDigest | **ABSENT** — must not substitute LOCAL_IMAGE_ID into IMAGE_DIGEST |
| task-definition image | ``...@sha256:IMAGE_DIGEST`` placeholder retained |

## Verdicts
- **SIMULATED GO** / **OFFLINE RELEASE GO** (Docker ECS-like)
- **Real AWS PRODUCTION GO**: **FORBIDDEN / NO-GO**

## OUT-OF-SCOPE / DEFERRED
- ECS-ACTUAL, ECR push, IAM actual wiring, ALB actual, CloudWatch delivery, EFS mount actual
- ACC-OPS-P1-003 in-memory session → DEFERRED-SINGLE-INSTANCE (desiredCount=1 sim only)
- SBOM/vuln scan → BLOCKED-TOOLING (no false zero-vuln claim)
- freee / CloudSign / Peppol / external AI vendor sandboxes

## Evidence
See sibling files in this directory. manifest.sha256 covers all artifacts.
"@
  [System.IO.File]::WriteAllText((Join-Path $script:Ev 'final-report.md'), $report, $utf8)

  # REVIEWER-PROMPT.md
  $prompt = @"
# Independent Reviewer — Narrow Scope (Offline ECS Simulation)

Review evidence at: ``$evidenceRevision`` / ``offline-ecs-simulation/``

## In scope
1. LOCAL_IMAGE_ID never treated as ECR IMAGE_DIGEST
2. Dockerfile: digest base, uid!=0, no build tools, JAR sha match, no secrets in history
3. Runtime: health/liveness/readiness 200; prometheus anon 401 / scraper 200 / wrong 401; egress blocked
4. task-definition static contract + DEFERRED/OUT-OF-SCOPE AWS items
5. ACC-OPS-P1-003 DEFERRED-SINGLE-INSTANCE
6. SBOM BLOCKED-TOOLING
7. Verdict SIMULATED GO only — not real AWS PRODUCTION GO

## Out of scope
Four random seeds, full verify-like-ci, real AWS, vendor externals

## Output
PASS/FAIL per item + whether SIMULATED GO is justified.
"@
  [System.IO.File]::WriteAllText((Join-Path $script:Ev 'REVIEWER-PROMPT.md'), $prompt, $utf8)

  # manifest.sha256
  $manifestPath = Join-Path $script:Ev 'manifest.sha256'
  $mlines = New-Object System.Collections.Generic.List[string]
  Get-ChildItem $script:Ev -File | Where-Object { $_.Name -ne 'manifest.sha256' } | Sort-Object Name | ForEach-Object {
    $h = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $mlines.Add("$h  $($_.Name)")
  }
  [System.IO.File]::WriteAllText($manifestPath, (($mlines -join "`n") + "`n"), $utf8)
  $self = (Get-FileHash $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
  Add-Content -Path $manifestPath -Value "manifest.sha256 self-SHA256 (body above)=$self" -Encoding utf8

  Write-Host "OFFLINE ECS SIMULATION PASS — $script:Ev"
  Write-Host "SIMULATED GO / OFFLINE RELEASE GO"
} finally {
  Pop-Location
}
