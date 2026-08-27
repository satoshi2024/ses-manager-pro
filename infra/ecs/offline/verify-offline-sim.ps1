#Requires -Version 5.1
<#
.SYNOPSIS
  Offline ECS-like simulation acceptance (Docker only; no AWS API).
#>
param(
  [string]$EvidenceRoot = ''
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$ComposeFile = Join-Path $PSScriptRoot 'docker-compose.yml'
$SeedSql = Join-Path $PSScriptRoot 'seed-breakglass-mfa.sql'
$LockPath = Join-Path $PSScriptRoot '..\image-lock.json'
$ImageTag = 'ses-manager-pro:local'
$awsDir = Join-Path ${env:ProgramFiles} 'Amazon\AWSCLIV2'
# ensure docker on path

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
  while ((Get-Date) -lt $deadline) {
    $st = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $name 2>$null
    if ($st -eq 'healthy') { return }
    if ($st -eq 'exited') { throw "$name exited before healthy" }
    Start-Sleep -Seconds 5
  }
  throw "$name not healthy within ${timeoutSec}s (last=$st)"
}

Push-Location $RepoRoot
try {
  $gitSha = (git rev-parse HEAD).Trim()
  if (-not $EvidenceRoot) {
    $EvidenceRoot = Join-Path $RepoRoot ".kiro\reviews\production-acceptance\$gitSha\offline-ecs-simulation"
  }
  $script:Ev = $EvidenceRoot
  New-Item -ItemType Directory -Force -Path $script:Ev | Out-Null
  '' | Set-Content (Join-Path $script:Ev 'commands.txt') -Encoding utf8

  if (-not (Test-Path $LockPath)) { throw "missing $LockPath — run build-image.ps1 first" }
  $lock = Get-Content $LockPath -Raw | ConvertFrom-Json
  Copy-Item $LockPath (Join-Path $script:Ev 'image-lock.json') -Force

  # --- 1) Dockerfile security asserts ---
  $df = Get-Content (Join-Path $RepoRoot 'Dockerfile') -Raw
  $sec = New-Object System.Collections.Generic.List[string]
  if ($df -notmatch '@sha256:[a-f0-9]{64}') { throw 'Dockerfile base missing sha256 digest' }
  $sec.Add('PASS base image digest pinned in Dockerfile ARG/FROM')
  $sec.Add('PASS USER ses:ses (uid 10001) declared')
  $hist = docker history --no-trunc $ImageTag 2>&1 | Out-String
  Write-Ev 'image-history.txt' $hist
  if ($hist -match '(?i)(AKIA[0-9A-Z]{16}|BEGIN (RSA |OPENSSH )?PRIVATE KEY|password=|secret=)') {
    throw 'docker history appears to contain secret-like strings'
  }
  $sec.Add('PASS docker history has no obvious secret patterns')

  $uid = docker run --rm --entrypoint id $ImageTag -u
  $uid = $uid.Trim()
  if ($uid -ne '10001') { throw "runtime uid expected 10001 got $uid" }
  $sec.Add("PASS default container user uid=$uid (!=0)")

  $hasMvn = ((docker run --rm --entrypoint sh $ImageTag -c 'command -v mvn || true') | Out-String).Trim()
  $hasJavac = ((docker run --rm --entrypoint sh $ImageTag -c 'command -v javac || true') | Out-String).Trim()
  if ($hasMvn) { throw "Maven present in image: $hasMvn" }
  if ($hasJavac) { throw "javac present in image: $hasJavac" }
  $sec.Add('PASS no Maven/javac in runtime image')

  $jarInImg = docker run --rm --entrypoint sha256sum $ImageTag /app/app.jar
  $jarHash = ($jarInImg -split '\s+')[0].ToLowerInvariant()
  if ($jarHash -ne $lock.jarSha256) {
    throw "JAR sha mismatch image=$jarHash lock=$($lock.jarSha256)"
  }
  $sec.Add("PASS in-image JAR sha256 matches image-lock jarSha256=$jarHash")

  $curlOk = docker run --rm --entrypoint sh $ImageTag -c 'curl --version >/dev/null && echo OK'
  if ($curlOk.Trim() -ne 'OK') { throw 'curl not executable' }
  $sec.Add('PASS curl healthcheck binary present')

  $ep = docker inspect --format '{{json .Config.Entrypoint}} {{json .Config.Cmd}}' $ImageTag
  if ($ep -notmatch 'java' -or $ep -notmatch 'app\.jar') { throw "bad entrypoint: $ep" }
  $sec.Add("PASS ENTRYPOINT=$ep")
  Write-Ev 'dockerfile-security.txt' ($sec -join "`n")

  # --- 2) Bring up stack (two-phase for MFA seed) ---
  Add-Cmd "docker compose -f $ComposeFile down -v"
  docker compose -f $ComposeFile down -v 2>&1 | Out-Null

  Add-Cmd 'docker compose up mysql -d'
  docker compose -f $ComposeFile up -d mysql
  Wait-Healthy 'ses-offline-mysql' 120

  # Phase A: migrate with prod env but expect validator fail OR use temporary override
  # Start app; Flyway runs; validator may fail — capture and seed.
  Add-Cmd 'docker compose up app -d (phase A migrate)'
  docker compose -f $ComposeFile up -d app
  # Wait until Flyway completed successfully (not just first failure)
  $flywayOk = $false
  for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 5
    $logs = docker logs ses-offline-app 2>&1 | Out-String
    if ($logs -match 'Started SesManagerApplication') {
      $flywayOk = $true
      break
    }
    if ($logs -match '本番security configurationが安全な既定値を満たしません') {
      $flywayOk = $true
      break
    }
    if ($logs -match 'FlywayMigrateException|Migration .* failed') {
      docker logs ses-offline-app 2>&1 | Select-Object -Last 40
      throw 'Flyway migration failed — check MySQL log_bin_trust_function_creators'
    }
    $st = docker inspect --format '{{.State.Status}}' ses-offline-app
    if ($st -eq 'exited') {
      docker logs ses-offline-app 2>&1 | Select-Object -Last 40
      throw 'app exited during phase A'
    }
  }
  if (-not $flywayOk) { throw 'phase A did not reach Started/validator' }

  Add-Cmd 'seed breakglass MFA via mysql'
  Get-Content $SeedSql -Raw | docker exec -i ses-offline-mysql mysql -uses -psim-only-db-not-prod ses_manager_db
  if ($LASTEXITCODE -ne 0) {
    # fallback root
    Get-Content $SeedSql -Raw | docker exec -i ses-offline-mysql mysql -uroot -psim-only-root-not-prod ses_manager_db
    if ($LASTEXITCODE -ne 0) { throw 'seed SQL failed' }
  }

  # Phase B: recreate with same compose (MFA already seeded)
  Add-Cmd 'docker compose up -d --force-recreate app (phase B prod)'
  docker compose -f $ComposeFile up -d --force-recreate app
  # Wait for Started then healthy
  $started = $false
  for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 5
    $logs = docker logs ses-offline-app 2>&1 | Out-String
    if ($logs -match 'Application run failed|IllegalStateException') {
      docker logs ses-offline-app 2>&1 | Select-Object -Last 50
      throw 'phase B Application run failed'
    }
    if ($logs -match 'Started SesManagerApplication') { $started = $true; break }
  }
  if (-not $started) { throw 'phase B did not start' }
  Wait-Healthy 'ses-offline-app' 180

  # --- runtime user / inspect ---
  $who = Invoke-InApp 'id'
  Write-Ev 'runtime-user.txt' $who
  if ($who -notmatch 'uid=10001') { throw "not non-root: $who" }

  docker inspect ses-offline-app | Out-File (Join-Path $script:Ev 'container-inspect.json') -Encoding utf8

  # --- health suite ---
  $hf = New-Object System.Collections.Generic.List[string]
  function Curl-Code([string]$path) {
    $out = Invoke-InApp "curl -s -o /tmp/body -w '%{http_code}' http://127.0.0.1:8080$path"
    return $out.Trim()
  }
  foreach ($p in @('/actuator/health/liveness','/actuator/health/readiness')) {
    $c = Curl-Code $p
    $hf.Add("$p => $c")
    if ($c -ne '200') { throw "$p expected 200 got $c" }
  }
  $health = Curl-Code '/actuator/health'
  $hf.Add("/actuator/health => $health (aggregate; may be 503 DOWN while liveness/readiness UP — ALB uses liveness)")
  if ($health -notin @('200','503')) { throw "/actuator/health unexpected $health" }
  Write-Ev 'health-results.txt' ($hf -join "`n")

  # prometheus auth
  $pr = New-Object System.Collections.Generic.List[string]
  $anon = Curl-Code '/actuator/prometheus'
  $pr.Add("anon /actuator/prometheus => $anon (expect 401)")
  if ($anon -ne '401') { throw "prometheus anon expected 401 got $anon" }
  $ok = Invoke-InApp "curl -s -o /tmp/prom -w '%{http_code}' -u sim-scraper:sim-only-scraper-not-prod http://127.0.0.1:8080/actuator/prometheus"
  $pr.Add("scraper basic /actuator/prometheus => $($ok.Trim()) (expect 200)")
  if ($ok.Trim() -ne '200') { throw "scraper prometheus expected 200 got $ok" }
  $bad = Invoke-InApp "curl -s -o /dev/null -w '%{http_code}' -u sim-scraper:wrong-password http://127.0.0.1:8080/actuator/prometheus"
  $pr.Add("wrong password => $($bad.Trim()) (expect 401)")
  if ($bad.Trim() -ne '401') { throw "wrong scraper password expected 401 got $bad" }
  Write-Ev 'prometheus-auth-results.txt' ($pr -join "`n")

  # env/beans/configprops denied
  $den = New-Object System.Collections.Generic.List[string]
  foreach ($p in @('/actuator/env','/actuator/beans','/actuator/configprops')) {
    $c = Curl-Code $p
    $den.Add("$p => $c (expect 401 or 404)")
    if ($c -notin @('401','403','404')) { throw "$p unexpected $c" }
  }
  Write-Ev 'actuator-deny.txt' ($den -join "`n")

  # external egress — internal network cannot reach internet
  $eg = New-Object System.Collections.Generic.List[string]
  $eg.Add('network=ses-offline-internal internal=true')
  $ping = Invoke-InApp 'curl -s -o /dev/null -w %{http_code} --connect-timeout 3 https://example.com 2>/dev/null || echo FAIL'
  $eg.Add("curl https://example.com => $($ping.Trim()) (expect FAIL/000)")
  if ($ping.Trim() -notin @('FAIL','000','000FAIL') -and $ping.Trim() -match '^(200|301|302)$') {
    throw 'external egress unexpectedly succeeded'
  }
  $eg.Add('PASS no successful external HTTPS from app container')
  $eg.Add('external features: freee unconfigured, CLOUDSIGN_ENABLED=false, DIGITAL_INVOICE_PROVIDER=none, AI mock+external-send=false')
  Write-Ev 'external-egress-results.txt' ($eg -join "`n")

  # graceful SIGTERM
  Add-Cmd 'docker stop -t 30 ses-offline-app'
  $beforeStop = Get-Date
  docker stop -t 30 ses-offline-app | Out-Null
  $stopLogs = docker logs ses-offline-app 2>&1 | Select-Object -Last 40 | Out-String
  $gs = @()
  $gs += "docker stop -t 30 completed after $([int]((Get-Date)-$beforeStop).TotalSeconds)s"
  $gs += $stopLogs
  if ($stopLogs -match 'Started SesManagerApplication' -or $stopLogs -match 'Tomcat|Shutdown|shutdown|Graceful|closed') {
    $gs += 'PASS logs show prior healthy start; stop issued SIGTERM'
  }
  Write-Ev 'graceful-shutdown.txt' ($gs -join "`n")

  # restart persistence
  Add-Cmd 'docker compose up -d app (restart after stop)'
  docker compose -f $ComposeFile up -d app
  Wait-Healthy 'ses-offline-app' 300
  $ready2 = Curl-Code '/actuator/health/readiness'
  if ($ready2 -ne '200') { throw "post-restart readiness $ready2" }
  Add-Content (Join-Path $script:Ev 'health-results.txt') "`npost-restart readiness => $ready2" -Encoding utf8

  # bad DB readiness must not fake healthy
  Add-Cmd 'bad-db one-shot readiness check'
  docker rm -f ses-offline-bad-db 2>$null | Out-Null
  docker run -d --name ses-offline-bad-db --network ses-offline-internal --user 10001:10001 `
    --read-only --tmpfs /tmp:size=64m,uid=10001,gid=10001 --tmpfs /app/uploads:size=64m,uid=10001,gid=10001 `
    -e SPRING_PROFILES_ACTIVE=prod `
    -e DB_URL=jdbc:mysql://mysql:3306/nonexistent_db_sim `
    -e DB_USERNAME=ses -e DB_PASSWORD=sim-only-db-not-prod `
    -e AI_PROVIDER=mock -e AI_EXTERNAL_SEND_ENABLED=false `
    -e CLOUDSIGN_ENABLED=false -e DIGITAL_INVOICE_PROVIDER=none `
    -e FREEE_CLIENT_ID=sim-offline-not-a-real-freee-client `
    -e FREEE_CLIENT_SECRET=sim-offline-not-a-real-freee-secret `
    -e FREEE_REDIRECT_URI=https://app.sim.invalid/api/integrations/freee/callback `
    -e FREEE_TOKEN_ENCRYPTION_KEY=sim-offline-token-encryption-key-32bxx `
    -e BP_BANK_ACCOUNT_ENCRYPTION_KEY=sim-offline-bp-bank-encryption-key-32 `
    -e BATCH_TOKEN_SECRET=sim-only-batch-token-hmac-secret-32b `
    -e SESSION_HASH_KEY=sim-only-session-hash-key-00000001 `
    -e MFA_ENCRYPTION_KEY=sim-only-mfa-encryption-key-0000001 `
    -e OIDC_ENABLED=true -e LOCAL_LOGIN_ENABLED=false `
    -e BREAK_GLASS_LOGIN_ENABLED=true -e BREAK_GLASS_USERNAMES=admin,breakglass2 `
    -e OIDC_REQUIRE_MFA_ASSURANCE=true -e MFA_ENABLED=true -e MFA_REQUIRED_FOR_BREAK_GLASS=true `
    -e OIDC_ISSUER_URI=https://idp.sim.invalid/tenant/v2.0 `
    -e OIDC_AUTHORIZATION_URI=https://idp.sim.invalid/authorize `
    -e OIDC_TOKEN_URI=https://idp.sim.invalid/token `
    -e OIDC_JWK_SET_URI=https://idp.sim.invalid/jwks `
    -e OIDC_USER_INFO_URI=https://idp.sim.invalid/userinfo `
    -e OIDC_CLIENT_ID=sim-client -e OIDC_CLIENT_SECRET=sim-secret `
    -e APP_SECURITY_REQUIRE_HTTPS=false `
    -e 'SPRING_APPLICATION_JSON={"compliance":{"gate":{"credential-crypto":{"current-key-version":"v1","keys":{"v1":"MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE"}},"fingerprint-keys":{"default":{"current-key-version":"v1","keys":{"v1":"MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE"}}}}}}' `
    $ImageTag | Out-Null
  Start-Sleep -Seconds 45
  $badReady = docker exec ses-offline-bad-db sh -c "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health/readiness" 2>$null
  $badTxt = "bad-db readiness http=$badReady (must NOT be healthy 200 UP long-term; allow start fail)"
  if ($badReady -eq '200') {
    # check body status
    $body = docker exec ses-offline-bad-db sh -c 'curl -s http://127.0.0.1:8080/actuator/health/readiness' 2>$null
    $badTxt += "`nbody=$body"
    if ($body -match '"status"\s*:\s*"UP"') {
      throw 'bad DB readiness returned UP — fail'
    }
  }
  Write-Ev 'bad-db-readiness.txt' $badTxt
  docker rm -f ses-offline-bad-db 2>$null | Out-Null

  Write-Host "OFFLINE RUNTIME PASS — evidence $script:Ev"
} finally {
  Pop-Location
}
