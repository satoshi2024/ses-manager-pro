#Requires -Version 5.1
# Static validation of the prod ECS task definition — no AWS API calls.
#
#   -Mode Template (default): checks infra/ecs/task-definition.prod.json, the repository template whose values
#     are placeholders (image @sha256:IMAGE_DIGEST, fs-PLACEHOLDER/fsap-PLACEHOLDER, *_PLACEHOLDER).
#   -Mode Actual: checks a definition with real values (the JSON you register, or the output of
#     `aws ecs describe-task-definition`). No placeholder may remain, the image must be pinned by a registry
#     digest and the EFS IDs must be real.
#     -Phase Initial (default): the first start in bootstrap mode (deploy-runbook sections 4 and 4.2).
#     -Phase Steady: the start that ends bootstrap (deploy-runbook section 5.5) and later starts.
#   The bootstrap reopen of deploy-runbook section 5.7 is not covered.
#
# Evidence goes to -EvidenceRoot, by default a new directory under the system temp directory (never inside the
# repository). Only setting names are written to the evidence, never values.
param(
  [ValidateSet('Template', 'Actual')][string]$Mode = 'Template',
  [ValidateSet('Initial', 'Steady')][string]$Phase = 'Initial',
  [string]$EvidenceRoot = '',
  [string]$TaskDefinitionPath = '',
  [switch]$EfsNegativeSelfTestOnly,
  [switch]$SkipInlineNegativeSelfTest
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$TdPath = if ($TaskDefinitionPath) {
  (Resolve-Path $TaskDefinitionPath).Path
} else {
  (Resolve-Path (Join-Path $PSScriptRoot '..\task-definition.prod.json')).Path
}
$gitSha = 'unknown'
try {
  $sha = git -C $RepoRoot rev-parse HEAD 2>$null
  if ($LASTEXITCODE -eq 0 -and $sha) { $gitSha = ([string]$sha).Trim() }
} catch { }
if (-not $EvidenceRoot) {
  $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
  $EvidenceRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ses-task-definition-validation-$stamp-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
}
if ($Mode -eq 'Template' -and $Phase -ne 'Initial') {
  throw 'Template mode checks the first-start template only; use -Mode Actual -Phase Steady for the start of deploy-runbook section 5.5'
}

function Assert-EfsRootDirectoryRule([object]$efsCfg) {
  $lines = New-Object System.Collections.Generic.List[string]
  $ap = $efsCfg.authorizationConfig.accessPointId
  $rd = $efsCfg.rootDirectory
  $hasAp = ($null -ne $ap -and [string]$ap -ne '')
  if ($hasAp) {
    if ($null -eq $rd -or [string]$rd -eq '' -or [string]$rd -eq '/') {
      $lines.Add("PASS accessPointId=$ap rootDirectory='$rd' (absent or /)")
    } else {
      # Always throw — never suppress. Fail-closed for ECS Access Point rule.
      throw "invalid EFS rootDirectory with accessPointId: $rd"
    }
  } else {
    $lines.Add("INFO no accessPointId — rootDirectory rules not applied")
  }
  return $lines
}

function Invoke-NegativeEfsSelfTest([string]$OutEvidenceRoot) {
  New-Item -ItemType Directory -Force -Path $OutEvidenceRoot | Out-Null
  $utf8 = New-Object System.Text.UTF8Encoding $false
  $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ses-efs-neg-" + [guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
  try {
    $tmpTd = Join-Path $tmpDir 'task-definition.bad.json'
    $badJson = @'
{
  "family": "ses-manager-prod-neg",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "executionRoleArn": "arn:aws:iam::ACCOUNT_ID:role/ecsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::ACCOUNT_ID:role/ecsTaskRole",
  "containerDefinitions": [
    {
      "name": "ses-manager",
      "image": "ACCOUNT_ID.dkr.ecr.ap-northeast-1.amazonaws.com/ses-manager-pro@sha256:IMAGE_DIGEST",
      "essential": true,
      "readonlyRootFilesystem": true,
      "linuxParameters": { "initProcessEnabled": true },
      "portMappings": [{ "containerPort": 8080, "protocol": "tcp" }],
      "environment": [{ "name": "SPRING_PROFILES_ACTIVE", "value": "prod" }],
      "secrets": [],
      "mountPoints": [
        { "sourceVolume": "tmp", "containerPath": "/tmp", "readOnly": false },
        { "sourceVolume": "uploads", "containerPath": "/app/uploads", "readOnly": false }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/ses-manager-prod",
          "awslogs-region": "ap-northeast-1",
          "awslogs-stream-prefix": "ses",
          "awslogs-create-group": "true"
        }
      },
      "healthCheck": {
        "command": ["CMD-SHELL", "curl -fsS http://127.0.0.1:8080/actuator/health/liveness >/dev/null || exit 1"],
        "interval": 30, "timeout": 5, "retries": 3, "startPeriod": 180
      }
    }
  ],
  "volumes": [
    { "name": "tmp" },
    {
      "name": "uploads",
      "efsVolumeConfiguration": {
        "fileSystemId": "fs-PLACEHOLDER",
        "rootDirectory": "/ses-manager/uploads",
        "transitEncryption": "ENABLED",
        "authorizationConfig": {
          "accessPointId": "fsap-PLACEHOLDER",
          "iam": "ENABLED"
        }
      }
    }
  ],
  "_offlineAcceptanceNotes": {
    "albTargetGroupHealthCheck": { "path": "/actuator/health/readiness" }
  }
}
'@
    [System.IO.File]::WriteAllText($tmpTd, $badJson, $utf8)

    $outFile = Join-Path $tmpDir 'child-out.txt'
    $errFile = Join-Path $tmpDir 'child-err.txt'
    $p = Start-Process -FilePath 'pwsh' -ArgumentList @(
      '-NoProfile', '-File', $PSCommandPath,
      '-TaskDefinitionPath', $tmpTd,
      '-EvidenceRoot', (Join-Path $tmpDir 'child-ev'),
      '-SkipInlineNegativeSelfTest'
    ) -Wait -PassThru -NoNewWindow `
      -RedirectStandardOutput $outFile -RedirectStandardError $errFile
    $validatorExit = $p.ExitCode
    $childOut = ''
    if (Test-Path $outFile) { $childOut += [System.IO.File]::ReadAllText($outFile) }
    if (Test-Path $errFile) { $childOut += "`n" + [System.IO.File]::ReadAllText($errFile) }

    $rejectionObserved = ($validatorExit -ne 0)
    $msgMatch = ($childOut -match 'invalid EFS rootDirectory with accessPointId')
    $accepted = ($childOut -match 'task-definition static validation PASS')

    $neg = New-Object System.Collections.Generic.List[string]
    $neg.Add("validatorExit=$validatorExit")
    $neg.Add("rejectionObserved=$rejectionObserved")
    $neg.Add("exceptionMessageMatch=$msgMatch")
    $neg.Add('--- child output ---')
    $neg.Add($childOut.Trim())

    if ($accepted -or (-not $rejectionObserved) -or (-not $msgMatch)) {
      [System.IO.File]::WriteAllText((Join-Path $OutEvidenceRoot 'efs-validation-negative.txt'), ($neg -join "`n"), $utf8)
      throw "EFS negative gate FAIL-CLOSED: illegal rootDirectory accepted or exception missing (exit=$validatorExit match=$msgMatch)"
    }

    $neg.Insert(0, 'PASS negative: rootDirectory=/ses-manager/uploads rejected (subprocess exit non-0 + invalid EFS rootDirectory)')
    [System.IO.File]::WriteAllText((Join-Path $OutEvidenceRoot 'efs-validation-negative.txt'), ($neg -join "`n"), $utf8)
    return @{
      validatorExit = $validatorExit
      rejectionObserved = $rejectionObserved
      childOut = $childOut
      exceptionMessage = ([regex]::Match($childOut, 'invalid EFS rootDirectory with accessPointId[^\r\n]*')).Value
    }
  } finally {
    Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
  }
}

if ($EfsNegativeSelfTestOnly) {
  $result = Invoke-NegativeEfsSelfTest -OutEvidenceRoot $EvidenceRoot
  Write-Host "EFS negative self-test PASS (child validatorExit=$($result.validatorExit))"
  exit 0
}

New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null

# PowerShell 7 turns ISO-8601 strings into DateTime and drops the offset; keep them as strings where possible.
$rawJson = Get-Content $TdPath -Raw
$doc = if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { $rawJson | ConvertFrom-Json -DateKind String } else { $rawJson | ConvertFrom-Json }
# `aws ecs describe-task-definition` wraps the definition in "taskDefinition".
$td = if ($doc.PSObject.Properties.Name -contains 'taskDefinition') { $doc.taskDefinition } else { $doc }
$lines = New-Object System.Collections.Generic.List[string]
function Ok([string]$m) { $script:lines.Add("PASS $m") }
function Info([string]$m) { $script:lines.Add("INFO $m") }
function Fail([string]$m) { $script:lines.Add("FAIL $m"); throw $m }
$utf8 = New-Object System.Text.UTF8Encoding $false
$evidenceFile = Join-Path $EvidenceRoot 'task-definition-validation.txt'
$lines.Add("INFO mode=$Mode phase=$Phase file=$([System.IO.Path]::GetFileName($TdPath)) repositoryHead=$gitSha checkedAtUtc=$((Get-Date).ToUniversalTime().ToString('o'))")

try {
if ($td.networkMode -ne 'awsvpc') { Fail 'networkMode!=awsvpc' } else { Ok 'networkMode=awsvpc' }
if ($td.requiresCompatibilities -notcontains 'FARGATE') { Fail 'missing FARGATE' } else { Ok 'FARGATE' }

$cpu = [string]$td.cpu; $mem = [string]$td.memory
if ($cpu -ne '1024' -or $mem -ne '2048') { Fail "cpu/mem $cpu/$mem" } else { Ok 'cpu/memory 1024/2048' }

# The app container is found by name, so a sidecar (for example ClamAV, runbook section 1.6 item 6) may be added.
$c = @($td.containerDefinitions | Where-Object { $_.name -eq 'ses-manager' }) | Select-Object -First 1
if (-not $c) { Fail 'container ses-manager missing' }
if (-not $c.essential) { Fail 'essential' } else { Ok 'essential=true' }
if (-not $c.readonlyRootFilesystem) { Fail 'readonlyRootFilesystem' } else { Ok 'readonlyRootFilesystem=true' }
if (-not $c.linuxParameters.initProcessEnabled) { Fail 'initProcessEnabled' } else { Ok 'initProcessEnabled=true' }
if ($c.portMappings[0].containerPort -ne 8080) { Fail 'port' } else { Ok 'port 8080' }
if ($Mode -eq 'Template') {
  if ($c.image -notmatch '@sha256:IMAGE_DIGEST$') { Fail 'IMAGE_DIGEST placeholder' } else { Ok 'ECR@IMAGE_DIGEST placeholder (not LOCAL_IMAGE_ID)' }
} else {
  if ($c.image -notmatch '@sha256:[0-9a-f]{64}$') { Fail 'image must be pinned by a registry digest (<registry>/<repository>@sha256:<64 hex>), never a tag or LOCAL_IMAGE_ID' }
  Ok 'image pinned by a registry digest'
}

$hc = ($c.healthCheck.command -join ' ')
if ($hc -notmatch 'actuator/health/liveness') { Fail 'container healthCheck must be liveness' } else { Ok 'ECS container healthCheck=liveness' }
if ($hc -match 'actuator/health/readiness') { Fail 'container healthCheck must NOT be readiness' }

if ($Mode -eq 'Template') {
  $alb = $td._offlineAcceptanceNotes.albTargetGroupHealthCheck.path
  if ($alb -ne '/actuator/health/readiness') { Fail "ALB path must be readiness, got $alb" } else { Ok 'ALB TG path=/actuator/health/readiness (DEFERRED until real AWS)' }
} else {
  Info 'the ALB target group health check is not part of a task definition: confirm /actuator/health/readiness with matcher 200 on the target group (forwarded-header-sanitize-notes 3.2)'
}

$uploads = $td.volumes | Where-Object { $_.name -eq 'uploads' }
if (-not $uploads.efsVolumeConfiguration) { Fail 'uploads missing efsVolumeConfiguration placeholder' }
if ($Mode -eq 'Template') {
  if ($uploads.efsVolumeConfiguration.fileSystemId -ne 'fs-PLACEHOLDER') { Fail 'EFS fs id placeholder' }
  if ($uploads.efsVolumeConfiguration.authorizationConfig.accessPointId -ne 'fsap-PLACEHOLDER') { Fail 'EFS accessPointId placeholder missing' }
  Ok 'uploads EFS accessPointId placeholder present'
} else {
  if ([string]$uploads.efsVolumeConfiguration.fileSystemId -notmatch '^fs-[0-9a-f]{8,}$') { Fail 'EFS fileSystemId must be a real fs-<hex> id' }
  if ([string]$uploads.efsVolumeConfiguration.authorizationConfig.accessPointId -notmatch '^fsap-[0-9a-f]{8,}$') { Fail 'EFS accessPointId must be a real fsap-<hex> id' }
  if ([string]$uploads.efsVolumeConfiguration.transitEncryption -ne 'ENABLED') { Fail 'EFS transitEncryption must be ENABLED' }
  Ok 'uploads EFS fileSystemId/accessPointId set, transit encryption enabled'
}
(Assert-EfsRootDirectoryRule $uploads.efsVolumeConfiguration) | ForEach-Object { $lines.Add($_) }
if ($Mode -eq 'Template') { Ok 'EFS-ACTUAL=DEFERRED (template static only)' }

$envNames = @($c.environment | ForEach-Object { $_.name })
$secretNames = @($c.secrets | ForEach-Object { $_.name })
$secretOnly = @('DB_PASSWORD','DB_USERNAME','DB_URL','OIDC_CLIENT_SECRET','BATCH_TOKEN_SECRET',
  'SESSION_HASH_KEY','MFA_ENCRYPTION_KEY','PWA_CONTEXT_SECRET','BP_BANK_ACCOUNT_ENCRYPTION_KEY',
  'CERTIFICATION_NUMBER_CRYPTO_KEYS_V1','COMPLIANCE_GATE_CREDENTIAL_CRYPTO_KEYS_V1',
  'COMPLIANCE_GATE_FINGERPRINT_KEYS_DEFAULT_KEYS_V1','FREEE_CLIENT_SECRET','FREEE_TOKEN_ENCRYPTION_KEY',
  'METRICS_SCRAPER_PASSWORD')
foreach ($f in $secretOnly) {
  if ($envNames -contains $f) { Fail "$f in environment" }
}
Ok 'no secrets in environment[] (Secrets Manager/SSM placeholders only)'

# deploy-runbook.md section 1.0: rows 1-26 are always required; rows 27-30 (freee) only with FREEE_ENABLED=true.
# Only the presence of each name is checked here; values stay placeholders in the template.
$requiredStartup = @('DB_URL','DB_USERNAME','DB_PASSWORD','TRUSTED_PROXIES','OIDC_TENANT_ID',
  'OIDC_ISSUER_URI','OIDC_AUTHORIZATION_URI','OIDC_TOKEN_URI','OIDC_JWK_SET_URI','OIDC_USER_INFO_URI',
  'OIDC_CLIENT_ID','OIDC_CLIENT_SECRET','MFA_ENCRYPTION_KEY','SESSION_HASH_KEY','BREAK_GLASS_USERNAMES',
  'BATCH_TOKEN_SECRET','PWA_CONTEXT_SECRET','BP_BANK_ACCOUNT_ENCRYPTION_KEY',
  'CERTIFICATION_NUMBER_CRYPTO_CURRENT_KEY_VERSION','CERTIFICATION_NUMBER_CRYPTO_KEYS_V1',
  'COMPLIANCE_CREDENTIAL_CURRENT_KEY_VERSION','COMPLIANCE_GATE_CREDENTIAL_CRYPTO_KEYS_V1',
  'COMPLIANCE_GATE_FINGERPRINT_KEY_TENANTS','COMPLIANCE_GATE_FINGERPRINT_KEYS_DEFAULT_CURRENT_KEY_VERSION',
  'COMPLIANCE_GATE_FINGERPRINT_KEYS_DEFAULT_KEYS_V1','ACCOUNTING_TENANT_IDS')
$freeeEnabled = @($c.environment | Where-Object { $_.name -eq 'FREEE_ENABLED' } | ForEach-Object { [string]$_.value })
if ($freeeEnabled -contains 'true') {
  $requiredStartup += @('FREEE_CLIENT_ID','FREEE_CLIENT_SECRET','FREEE_REDIRECT_URI','FREEE_TOKEN_ENCRYPTION_KEY')
}
$allNames = $envNames + $secretNames
$missing = @($requiredStartup | Where-Object { $allNames -notcontains $_ })
if ($missing.Count -gt 0) { Fail ("prod startup settings missing (deploy-runbook 1.0): " + ($missing -join ',')) }
Ok "prod startup settings present ($($requiredStartup.Count) names for deploy-runbook 1.0; FREEE_ENABLED=$($freeeEnabled -join ','))"

function EnvValues([string]$name) {
  @($c.environment | Where-Object { $_.name -eq $name } | ForEach-Object {
      if ($_.value -is [datetime]) { Fail "$name was read as a date and lost its offset: run with PowerShell 7.5 or later, or Windows PowerShell 5.1" }
      ([string]$_.value).Trim()
    })
}

# deploy-runbook.md section 1.0 "settings that run without stopping the start": ClamAV and the metrics scraper.
# Their values depend on decisions still open (section 1.6 items 6 and 7), but the names must be there.
$requiredOperational = @('FILE_SCANNER_HOST','FILE_SCANNER_PORT','METRICS_SCRAPER_ENABLED','METRICS_SCRAPER_USERNAME','METRICS_SCRAPER_PASSWORD')
$missingOperational = @($requiredOperational | Where-Object { $allNames -notcontains $_ })
if ($missingOperational.Count -gt 0) { Fail ("operational settings missing (deploy-runbook 1.0): " + ($missingOperational -join ',')) }
if ($Mode -eq 'Actual') {
  if (-not ((EnvValues 'FILE_SCANNER_PORT') | Where-Object { $_ -match '^[0-9]{1,5}$' })) { Fail 'FILE_SCANNER_PORT must be a port number' }
}
Ok 'ClamAV (FILE_SCANNER_HOST/PORT) and metrics scraper (METRICS_SCRAPER_*) settings present'

# deploy-runbook.md section 1.0 "values that must not be set": each of these stops the prod start.
$forbidden = [ordered]@{
  'LOCAL_LOGIN_ENABLED' = 'true'; 'OIDC_ENABLED' = 'false'; 'BREAK_GLASS_LOGIN_ENABLED' = 'false';
  'OIDC_REQUIRE_MFA_ASSURANCE' = 'false'; 'MFA_ENABLED' = 'false'; 'MFA_REQUIRED_FOR_BREAK_GLASS' = 'false';
  'ALLOW_UNTRACKED_SESSIONS' = 'true'; 'AI_EXTERNAL_SEND_ENABLED' = 'true'
}
foreach ($name in $forbidden.Keys) {
  if ((EnvValues $name | ForEach-Object { $_.ToLowerInvariant() }) -contains $forbidden[$name]) { Fail "$name=$($forbidden[$name]) stops the prod start (deploy-runbook 1.0)" }
}
if ($allNames -contains 'SPRING_FLYWAY_ENABLED') { Fail 'SPRING_FLYWAY_ENABLED must not be set (deploy-runbook 4)' }
if (((EnvValues 'CLOUDSIGN_ENABLED') | ForEach-Object { $_.ToLowerInvariant() }) -contains 'true' -and $allNames -notcontains 'CLOUDSIGN_CLIENT_ID') {
  Fail 'CLOUDSIGN_ENABLED=true without CLOUDSIGN_CLIENT_ID stops the prod start (deploy-runbook 1.0)'
}
Ok 'no value that deploy-runbook 1.0 forbids'

$breakGlass = @(EnvValues 'BREAK_GLASS_USERNAMES')
$featureSwitches = @($c.environment | Where-Object { $_.name -match '^(FEATURE_NF[0-9A-Z_]*_ENABLED|PRIVACY_RETENTION_ENABLED|PRIVACY_DSAR_ENABLED)$' } | ForEach-Object { $_.name })
if ($Phase -eq 'Initial') {
  # deploy-runbook.md sections 4 and 4.2: one task without the scheduler, in bootstrap mode, all feature switches unset.
  if (-not ((EnvValues 'APP_SCHEDULING_ENABLED') -contains 'false')) {
    Fail 'APP_SCHEDULING_ENABLED=false missing: the first start runs without the scheduler, and a missing value turns it on (deploy-runbook 4)'
  }
  if (-not ((EnvValues 'SECURITY_BOOTSTRAP_ENABLED') -contains 'true')) { Fail 'SECURITY_BOOTSTRAP_ENABLED=true missing for the first start (deploy-runbook 4.2)' }
  $expires = @(EnvValues 'SECURITY_BOOTSTRAP_EXPIRES_AT')
  if ($expires.Count -ne 1 -or -not $expires[0]) { Fail 'SECURITY_BOOTSTRAP_EXPIRES_AT missing for the first start (deploy-runbook 4.2)' }
  if ($Mode -eq 'Actual') {
    if ($expires[0] -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$') {
      Fail 'SECURITY_BOOTSTRAP_EXPIRES_AT must be ISO-8601 with an offset, e.g. 2026-11-14T18:00:00+09:00 (deploy-runbook 4.2)'
    }
    $deadline = [DateTimeOffset]::Parse($expires[0], [System.Globalization.CultureInfo]::InvariantCulture)
    $now = [DateTimeOffset]::UtcNow
    if ($deadline -le $now) { Fail 'SECURITY_BOOTSTRAP_EXPIRES_AT is already past (deploy-runbook 4.2)' }
    if ($deadline -gt $now.AddHours(24)) {
      Info 'SECURITY_BOOTSTRAP_EXPIRES_AT is more than 24 hours ahead of now: the start refuses it unless it is at most 24 hours after the start time'
    }
  }
  if ($breakGlass.Count -ne 1 -or $breakGlass[0] -ne '') { Fail 'BREAK_GLASS_USERNAMES must be present and empty during bootstrap (deploy-runbook 4.2)' }
  if ($allNames -contains 'SECURITY_BOOTSTRAP_REOPEN_TICKET') { Fail 'SECURITY_BOOTSTRAP_REOPEN_TICKET must not be set for the first bootstrap (deploy-runbook 4.2; 5.7 only)' }
  if ($featureSwitches.Count -gt 0) { Fail ('feature switches must stay unset for the first release (feature-switches 3.1): ' + ($featureSwitches -join ',')) }
  Ok 'first start: APP_SCHEDULING_ENABLED=false, bootstrap mode on, BREAK_GLASS_USERNAMES empty, no feature switch'
} else {
  # deploy-runbook.md section 5.5: bootstrap off and exactly two break-glass names.
  if ((EnvValues 'SECURITY_BOOTSTRAP_ENABLED' | ForEach-Object { $_.ToLowerInvariant() }) | Where-Object { $_ -ne 'false' }) {
    Fail 'SECURITY_BOOTSTRAP_ENABLED must be removed once bootstrap ends (deploy-runbook 5.5)'
  }
  foreach ($name in @('SECURITY_BOOTSTRAP_EXPIRES_AT', 'SECURITY_BOOTSTRAP_REOPEN_TICKET')) {
    if ($allNames -contains $name) { Fail "$name must be removed once bootstrap ends (deploy-runbook 5.5)" }
  }
  $names = @(if ($breakGlass.Count -eq 1) { $breakGlass[0] -split ',' | ForEach-Object { $_.Trim() } })
  $distinct = @($names | ForEach-Object { $_.ToLowerInvariant() } | Select-Object -Unique)
  if ($names.Count -ne 2 -or $distinct.Count -ne 2 -or @($names | Where-Object { $_ -notmatch '^[A-Za-z0-9._-]+$' }).Count -gt 0) {
    Fail 'BREAK_GLASS_USERNAMES must hold exactly two distinct names of [A-Za-z0-9._-] after bootstrap (deploy-runbook 1.0 row 15, 5.5)'
  }
  Ok 'after bootstrap: bootstrap settings removed, BREAK_GLASS_USERNAMES has two names'
}

if ($Mode -eq 'Actual') {
  # No placeholder may survive into the definition that is registered.
  $placeholder = 'PLACEHOLDER|ACCOUNT_ID|IMAGE_DIGEST'
  $left = New-Object System.Collections.Generic.List[string]
  foreach ($e in @($c.environment)) { if ([string]$e.value -match $placeholder) { $left.Add("environment:$($e.name)") } }
  foreach ($s in @($c.secrets)) { if ([string]$s.valueFrom -match $placeholder) { $left.Add("secrets:$($s.name)") } }
  foreach ($field in @('executionRoleArn', 'taskRoleArn')) { if ([string]$td.$field -match $placeholder) { $left.Add($field) } }
  if ([string]$c.image -match $placeholder) { $left.Add('image') }
  if ($left.Count -gt 0) { Fail ('placeholders left: ' + ($left -join ',')) }
  Ok 'no placeholder left in environment, secrets, roles or image'

  $trusted = @(EnvValues 'TRUSTED_PROXIES')
  if (($trusted -join ',') -match '(^|,)\s*(127\.|::1)') { Fail 'TRUSTED_PROXIES must not contain a loopback address (deploy-runbook SRE-3)' }
  Ok 'TRUSTED_PROXIES has no loopback'

  # deploy-runbook.md SRE-9: admin and the break-glass names are always refused (401) on the scrape endpoint.
  $scraper = @(EnvValues 'METRICS_SCRAPER_USERNAME')
  $refused = @('admin') + @(if ($breakGlass.Count -eq 1) { $breakGlass[0] -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ } })
  foreach ($u in $scraper) {
    if ($refused | Where-Object { $_.ToLowerInvariant() -eq $u.ToLowerInvariant() }) { Fail 'METRICS_SCRAPER_USERNAME must not be admin or a break-glass name (deploy-runbook SRE-9)' }
  }
  Ok 'METRICS_SCRAPER_USERNAME is not admin or a break-glass name'
}

# deploy-runbook.md sections 1.0 / 4: the JVM time zone must be Asia/Tokyo (account lock expiry and audit timestamps use it).
# TZ=Asia/Tokyo is required; a JAVA_TOOL_OPTIONS override must also keep -Duser.timezone=Asia/Tokyo.
$tz = @($c.environment | Where-Object { $_.name -eq 'TZ' } | ForEach-Object { [string]$_.value })
if (-not ($tz -contains 'Asia/Tokyo')) { Fail 'TZ=Asia/Tokyo missing in environment (deploy-runbook 1.0 / 4)' }
$jto = @($c.environment | Where-Object { $_.name -eq 'JAVA_TOOL_OPTIONS' } | ForEach-Object { [string]$_.value })
foreach ($value in $jto) {
  if ($value -notmatch '-Duser\.timezone=Asia/Tokyo') { Fail 'JAVA_TOOL_OPTIONS override drops -Duser.timezone=Asia/Tokyo' }
}
Ok 'JVM time zone Asia/Tokyo (TZ)'

if ($c.logConfiguration.logDriver -ne 'awslogs') { Fail 'awslogs driver' } else { Ok 'awslogs placeholder present' }
if ($Mode -eq 'Template') {
  Ok 'CloudWatch-ACTUAL=DEFERRED (no log delivery verified)'
  Ok 'executionRoleArn/taskRoleArn=IAM placeholders (IAM-ACTUAL=DEFERRED)'
  Ok 'ALB/SG/circuit-breaker/autoscaling=DEFERRED external IaC'
  Ok 'ECR push/registryDigest=OUT-OF-SCOPE (no AWS account)'
} else {
  Info 'not checked here: secret values behind the ARNs (the start validators check them), ALB, security groups, service desired count and deployment settings (deploy-runbook SRE-5)'
}
} catch {
  $message = $_.Exception.Message
  if (-not ($lines | Where-Object { $_ -eq "FAIL $message" })) { $lines.Add("FAIL $message") }
  throw
} finally {
  [System.IO.File]::WriteAllLines($evidenceFile, $lines.ToArray(), $utf8)
  Write-Host "evidence: $evidenceFile"
}

if (-not $SkipInlineNegativeSelfTest) {
  $null = Invoke-NegativeEfsSelfTest -OutEvidenceRoot $EvidenceRoot
}

Write-Host 'task-definition static validation PASS'
