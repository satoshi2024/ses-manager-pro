#Requires -Version 5.1
<#
.SYNOPSIS
  Offline AWS / local ECS-like simulation (Docker only; no AWS API).
  Covers REV-ECS-P1-001/002, P2-001/002/003.
#>
param(
  [string]$EvidenceRoot = '',
  [switch]$EgressNegativeSelfTestOnly
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$ComposeFile = Join-Path $PSScriptRoot 'docker-compose.yml'
$SeedSql = Join-Path $PSScriptRoot 'seed-breakglass-mfa.sql'
$LockPath = Join-Path $PSScriptRoot '..\image-lock.json'
$ImageTag = 'ses-manager-pro:local'
$ImageSourceRevisionFixed = 'b1985327107207c824554e7e0058ad38d1b0cb04'
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
    $EvidenceRoot = Join-Path $RepoRoot ".kiro\reviews\production-acceptance\$evidenceRevision\offline-ecs-simulation-2.1"
  }
  $script:Ev = $EvidenceRoot
  New-Item -ItemType Directory -Force -Path $script:Ev | Out-Null
  '' | Set-Content (Join-Path $script:Ev 'commands.txt') -Encoding utf8

  if (-not (Test-Path $LockPath)) { throw "missing $LockPath" }
  $lock = Get-Content $LockPath -Raw | ConvertFrom-Json

  # --- egress negative self-test first ---
  Write-Ev 'egress-negative-selftest.txt' (Test-EgressHarnessNegative)

  # --- Dockerfile security ---
  $df = Get-Content (Join-Path $RepoRoot 'Dockerfile') -Raw
  $sec = New-Object System.Collections.Generic.List[string]
  if ($df -notmatch '@sha256:[a-f0-9]{64}') { throw 'Dockerfile base missing sha256 digest' }
  $sec.Add('PASS base digest pinned')
  $uid = (docker run --rm --entrypoint id $ImageTag -u).Trim()
  if ($uid -ne '10001') { throw "uid=$uid" }
  $sec.Add("PASS uid=$uid")
  $hist = docker history --no-trunc $ImageTag 2>&1 | Out-String
  Write-Ev 'image-history.txt' $hist
  if ($hist -match '(?i)(AKIA[0-9A-Z]{16}|BEGIN (RSA |OPENSSH )?PRIVATE KEY)') {
    throw 'history secret pattern'
  }
  $sec.Add('PASS history no secret patterns')
  $jarInImg = docker run --rm --entrypoint sha256sum $ImageTag /app/app.jar
  $jarHash = ($jarInImg -split '\s+')[0].ToLowerInvariant()
  if ($jarHash -ne $lock.jarSha256) { throw "JAR sha mismatch $jarHash vs $($lock.jarSha256)" }
  $sec.Add("PASS jar sha=$jarHash")
  Write-Ev 'dockerfile-security.txt' ($sec -join "`n")

  # --- bring up ---
  Add-Cmd "docker compose -f $ComposeFile down -v"
  docker compose -f $ComposeFile down -v 2>&1 | Out-Null

  # Pre-create uploads volume owned by 10001 (REV-ECS-P1-002)
  docker volume create offline_ses-offline-uploads 2>$null | Out-Null
  Add-Cmd 'chown uploads volume to 10001 and mkdir .ops'
  docker run --rm -v offline_ses-offline-uploads:/data mysql:8.0.36 `
    sh -c 'rm -rf /data/lost+found /data/* /data/.[!.]* 2>/dev/null; mkdir -p /data/.ops /data/sim && chown -R 10001:10001 /data'

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
  $live = Curl-Code '/actuator/health/liveness'
  $ready = Curl-Code '/actuator/health/readiness'
  $hf.Add("baseline liveness => $live body=$(Curl-Body '/actuator/health/liveness')")
  $hf.Add("baseline readiness => $ready body=$(Curl-Body '/actuator/health/readiness')")
  if ($live -ne '200') { throw "liveness $live" }
  if ($ready -ne '200') { throw "readiness $ready" }
  $hf.Add('ALB convention: /actuator/health/readiness (NOT liveness)')
  $hf.Add('ECS container healthCheck: /actuator/health/liveness')

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
  $up += 'EFS-ACTUAL=BLOCKED (template only in task-definition.prod.json)'
  Write-Ev 'uploads-persistence.txt' ($up -join "`n")

  # --- egress ---
  $ping = Invoke-InApp 'curl -s -o /dev/null -w %{http_code} --connect-timeout 3 https://example.com 2>/dev/null || echo FAIL'
  Assert-EgressResult $ping 'example.com'
  Write-Ev 'external-egress-results.txt' @"
network=ses-offline-internal internal=true
curl https://example.com => $(($ping | Out-String).Trim())
PASS exact allow-list FAIL|000|000FAIL
CloudSign=false Peppol=none AI=mock
freee=sim placeholders; outbound blocked
"@

  # --- REV-ECS-P2-003 graceful shutdown ---
  Add-Cmd 'marker then docker stop -t 40 ses-offline-app'
  $marker = "SHUTDOWN-TEST-MARKER-$(Get-Date -Format 'yyyyMMddHHmmss')"
  Invoke-InApp "mkdir -p /app/uploads/.ops && echo $marker >> /app/uploads/.ops/spring.log"
  $modeLogs = docker logs ses-offline-app 2>&1 | Out-String
  if ($modeLogs -notmatch 'server\.shutdown effective value=graceful') {
    throw 'server.shutdown effective value=graceful not observed at runtime'
  }
  $t0 = Get-Date
  $ErrorActionPreference = 'Continue'
  docker stop -t 40 ses-offline-app 2>&1 | Out-Null
  $ErrorActionPreference = 'Stop'
  $elapsed = [Math]::Max(0, [int]((Get-Date) - $t0).TotalSeconds)
  $exitCode = (docker inspect --format '{{.State.ExitCode}}' ses-offline-app).Trim()
  $oom = (docker inspect --format '{{.State.OOMKilled}}' ses-offline-app).Trim()
  $stopLogs = docker logs ses-offline-app 2>&1 | Out-String
  $fileLogStr = (docker run --rm -v offline_ses-offline-uploads:/data mysql:8.0.36 `
    sh -c 'cat /data/.ops/spring.log 2>/dev/null || true' | Out-String)
  # Only accept graceful signals that appear AFTER this test's marker (ignore prior recreates)
  $idx = $fileLogStr.IndexOf($marker)
  $afterMarker = if ($idx -ge 0) { $fileLogStr.Substring($idx) } else { '' }
  # Prefer after-marker file log (survives Desktop stdout drop). Also allow docker logs.
  $hasCommenceFile = $afterMarker -match 'Commencing graceful shutdown'
  $hasCompleteFile = $afterMarker -match 'Graceful shutdown complete'
  $hasCommenceDock = $stopLogs -match 'Commencing graceful shutdown'
  $hasCompleteDock = $stopLogs -match 'Graceful shutdown complete'
  $opsList = (docker run --rm -v offline_ses-offline-uploads:/data mysql:8.0.36 `
    sh -c 'ls -la /data/.ops' | Out-String)
  $hasCommenceEv = $opsList -match 'graceful-commence\.txt'
  $hasCompleteEv = $opsList -match 'graceful-complete\.txt'
  $hasJvmHook = $opsList -match 'jvm-shutdown-hook\.txt'
  $hasCommence = $hasCommenceFile -or $hasCommenceDock
  $hasComplete = $hasCompleteFile -or $hasCompleteDock
  $gs = New-Object System.Collections.Generic.List[string]
  $gs.Add("exitCode=$exitCode OOMKilled=$oom stopElapsedSec=$elapsed")
  $gs.Add("marker=$marker markerFoundInFile=$($idx -ge 0)")
  $gs.Add("Commencing graceful shutdown file=$hasCommenceFile docker=$hasCommenceDock evidenceFile=$hasCommenceEv")
  $gs.Add("Graceful shutdown complete file=$hasCompleteFile docker=$hasCompleteDock evidenceFile=$hasCompleteEv")
  $gs.Add("jvm-shutdown-hook.txt=$hasJvmHook")
  $gs.Add('logSources=docker-logs+uploads/.ops/spring.log(after-marker)+ops-evidence')
  if (-not $hasCommence -or -not $hasComplete) {
    Write-Ev 'graceful-shutdown-docker-logs-tail.txt' $stopLogs.Substring([Math]::Max(0, $stopLogs.Length - 6000))
    Write-Ev 'graceful-shutdown-file-log-after-marker.txt' $afterMarker
    Write-Ev 'graceful-shutdown-ops-ls.txt' $opsList
    Write-Ev 'graceful-shutdown.txt' ($gs -join "`n")
    throw 'missing graceful shutdown completion signals'
  }
  if (-not $hasCommenceEv -or -not $hasCompleteEv) {
    Write-Ev 'graceful-shutdown-ops-ls.txt' $opsList
    Write-Ev 'graceful-shutdown.txt' ($gs -join "`n")
    throw 'missing graceful shutdown evidence files under uploads/.ops'
  }
  # SIGTERM → JVM often exits 143 (128+15) after hooks; 0 also OK.
  if ($exitCode -notin @('0', '143')) { throw "unexpected exit $exitCode (want 0 or 143 after SIGTERM)" }
  if ($oom -eq 'true') { throw 'OOMKilled' }
  $gs.Add('PASS graceful shutdown VERIFIED')
  Write-Ev 'graceful-shutdown.txt' ($gs -join "`n")
  Write-Ev 'graceful-shutdown-file-log-after-marker.txt' $afterMarker
  Write-Ev 'graceful-shutdown-ops-ls.txt' $opsList

  # restart for final healthy evidence
  docker compose -f $ComposeFile up -d app
  Wait-Healthy 'ses-offline-app' 180

  # provenance lock for evidence
  $imgSrc = $ImageSourceRevisionFixed
  if ($lock.jarSha256 -and $lock.imageSourceRevision) {
    # JAR changed this round → imageSourceRevision is the build-time git revision in image-lock.json
    if ($lock.imageSourceRevision -ne $ImageSourceRevisionFixed) {
      $imgSrc = [string]$lock.imageSourceRevision
    }
  }
  $prov = [ordered]@{
    schemaVersion = 3
    freezeBaseline = $FreezeBaseline
    imageSourceRevision = $imgSrc
    evidenceRevision = $evidenceRevision
    note = 'imageSourceRevision=git HEAD at docker build that produced jar/image; evidenceRevision=tip SHA of this evidence pack; freezeBaseline=acceptance freeze. When jarSha256/localImageId unchanged vs b1985327 lineage, keep imageSourceRevision=b1985327 (no infinite rebuild for self-ref).'
    jarSha256 = $lock.jarSha256
    baseImageDigest = $lock.baseImageDigest
    localImageId = $lock.localImageId
    registryDigest = $null
    registryDigestStatus = 'ABSENT'
    ecrPush = 'NOT_PERFORMED'
    ecsDeploy = 'NOT_PERFORMED'
    efsActual = 'BLOCKED'
    p3AptReproducibility = 'OPEN'
  }
  $utf8 = New-Object System.Text.UTF8Encoding $false
  [System.IO.File]::WriteAllText((Join-Path $script:Ev 'image-lock.json'), ($prov | ConvertTo-Json -Depth 5), $utf8)

  Write-Host "OFFLINE 2.1 RUNTIME PASS — $script:Ev"
} finally {
  Pop-Location
}
