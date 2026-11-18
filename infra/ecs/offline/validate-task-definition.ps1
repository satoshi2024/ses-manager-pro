#Requires -Version 5.1
# Static validation of the prod ECS task definition - no AWS API calls.
#
#   -Mode Template (default): checks infra/ecs/task-definition.prod.json, the repository template whose values
#     are placeholders (images @sha256:IMAGE_DIGEST / @sha256:CLAMAV_IMAGE_DIGEST, fs-PLACEHOLDER,
#     fsap-*PLACEHOLDER, *_PLACEHOLDER).
#   -Mode Actual: checks a definition with real values (the JSON you register, or the output of
#     `aws ecs describe-task-definition`). No placeholder may remain, every image must be pinned by a registry
#     digest and the EFS IDs must be real.
#     -Phase Initial (default): the first start in bootstrap mode (deploy-runbook sections 4 and 4.2).
#     -Phase Steady -Scheduler Enabled|Disabled: the start that ends bootstrap (deploy-runbook section 5.5) and
#       every later start. -Scheduler is the expected APP_SCHEDULING_ENABLED: Disabled before the scheduler
#       sign-offs (section 4) and for the drain revision of a release (section 2), Enabled for the normal state.
#   The bootstrap reopen of deploy-runbook section 5.7 is not covered.
#
# The task holds exactly two containers, the app (ses-manager) and the ClamAV sidecar (clamav), and the app
# mounts the uploads volume read-write and the NF07 trust volume (privacy-trust) read-only.
#
# Evidence goes to -EvidenceRoot, by default a new directory under the system temp directory (never inside the
# repository). The evidence names settings and states what was expected; it never copies a setting's value.
# The inline negative self-test starts a child process with the same PowerShell that runs this script, so it
# also works under Windows PowerShell 5.1 without pwsh.
param(
  [ValidateSet('Template', 'Actual')][string]$Mode = 'Template',
  [ValidateSet('Initial', 'Steady')][string]$Phase = 'Initial',
  [ValidateSet('Enabled', 'Disabled')][string]$Scheduler,
  [string]$EvidenceRoot = '',
  [string]$TaskDefinitionPath = '',
  [switch]$EfsNegativeSelfTestOnly,
  [switch]$SkipInlineNegativeSelfTest
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$TemplatePath = (Resolve-Path (Join-Path $PSScriptRoot '..\task-definition.prod.json')).Path
$TdPath = if ($TaskDefinitionPath) { (Resolve-Path $TaskDefinitionPath).Path } else { $TemplatePath }
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
if ($Phase -eq 'Steady' -and -not $Scheduler) {
  throw '-Phase Steady needs -Scheduler Enabled or -Scheduler Disabled: the expected APP_SCHEDULING_ENABLED (deploy-runbook sections 2 and 4)'
}
if ($Phase -eq 'Initial' -and $Scheduler -and $Scheduler -ne 'Disabled') {
  throw 'the first start always runs with the scheduler disabled (deploy-runbook 4); drop -Scheduler or use Disabled'
}
$ExpectedScheduler = if ($Phase -eq 'Initial') { 'Disabled' } else { $Scheduler }

$AppContainer = 'ses-manager'
$ScannerContainer = 'clamav'
$TrustVolume = 'privacy-trust'

function Read-Json([string]$path) {
  # PowerShell 7 turns ISO-8601 strings into DateTime and drops the offset; keep them as strings where possible.
  $raw = Get-Content $path -Raw
  if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { return ($raw | ConvertFrom-Json -DateKind String) }
  return ($raw | ConvertFrom-Json)
}

function Assert-EfsRootDirectoryRule([string]$volumeName, [object]$efsCfg) {
  $lines = New-Object System.Collections.Generic.List[string]
  $ap = $efsCfg.authorizationConfig.accessPointId
  $rd = $efsCfg.rootDirectory
  $hasAp = ($null -ne $ap -and [string]$ap -ne '')
  if ($hasAp) {
    if ($null -eq $rd -or [string]$rd -eq '' -or [string]$rd -eq '/') {
      $lines.Add("PASS volume $volumeName has an accessPointId and rootDirectory is absent or /")
    } else {
      # Always throw - never suppress. Fail-closed for ECS Access Point rule.
      throw "invalid EFS rootDirectory with accessPointId (volume $volumeName): rootDirectory must be absent or / when an access point is set"
    }
  } else {
    $lines.Add("INFO volume $volumeName has no accessPointId - rootDirectory rules not applied")
  }
  return $lines
}

function Invoke-NegativeEfsSelfTest([string]$OutEvidenceRoot) {
  New-Item -ItemType Directory -Force -Path $OutEvidenceRoot | Out-Null
  $utf8 = New-Object System.Text.UTF8Encoding $false
  $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ses-efs-neg-" + [guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
  try {
    # The repository template with an illegal rootDirectory on the uploads volume: everything before the EFS
    # rule passes, so the child must stop exactly at the rule.
    $bad = Read-Json $TemplatePath
    $uploadsCfg = ($bad.volumes | Where-Object { $_.name -eq 'uploads' }).efsVolumeConfiguration
    $uploadsCfg | Add-Member -NotePropertyName rootDirectory -NotePropertyValue '/ses-manager/uploads' -Force
    $tmpTd = Join-Path $tmpDir 'task-definition.bad.json'
    [System.IO.File]::WriteAllText($tmpTd, ($bad | ConvertTo-Json -Depth 32), $utf8)

    $outFile = Join-Path $tmpDir 'child-out.txt'
    $errFile = Join-Path $tmpDir 'child-err.txt'
    $self = (Get-Process -Id $PID).Path
    $arguments = @('-NoProfile')
    if ($PSVersionTable.PSEdition -eq 'Desktop') { $arguments += @('-ExecutionPolicy', 'Bypass') }
    $arguments += @('-File', $PSCommandPath, '-TaskDefinitionPath', $tmpTd, '-EvidenceRoot', (Join-Path $tmpDir 'child-ev'), '-SkipInlineNegativeSelfTest')
    $p = Start-Process -FilePath $self -ArgumentList $arguments -Wait -PassThru -NoNewWindow `
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

    $neg.Insert(0, 'PASS negative: uploads rootDirectory rejected (subprocess exit non-0 + invalid EFS rootDirectory)')
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

$doc = Read-Json $TdPath
# `aws ecs describe-task-definition` wraps the definition in "taskDefinition".
$td = if ($doc.PSObject.Properties.Name -contains 'taskDefinition') { $doc.taskDefinition } else { $doc }
$lines = New-Object System.Collections.Generic.List[string]
function Ok([string]$m) { $script:lines.Add("PASS $m") }
function Info([string]$m) { $script:lines.Add("INFO $m") }
function Fail([string]$m) { $script:lines.Add("FAIL $m"); throw $m }
$utf8 = New-Object System.Text.UTF8Encoding $false
$evidenceFile = Join-Path $EvidenceRoot 'task-definition-validation.txt'
$lines.Add("INFO mode=$Mode phase=$Phase expectedScheduler=$ExpectedScheduler file=$([System.IO.Path]::GetFileName($TdPath)) repositoryHead=$gitSha checkedAtUtc=$((Get-Date).ToUniversalTime().ToString('o'))")

try {
if ($td.networkMode -ne 'awsvpc') { Fail 'networkMode!=awsvpc' } else { Ok 'networkMode=awsvpc' }
if ($td.requiresCompatibilities -notcontains 'FARGATE') { Fail 'missing FARGATE' } else { Ok 'FARGATE' }

# Task size: app 1024 CPU / 2048 MiB + ClamAV sidecar 1024 / 4096 (deploy-runbook section 1.6 item 6).
if ([string]$td.cpu -ne '2048' -or [string]$td.memory -ne '6144') { Fail 'task cpu/memory must be the template values 2048/6144 (change the template and this check together)' }
Ok 'task cpu/memory 2048/6144'

# Containers: exactly the app and the ClamAV sidecar.
$containerNames = @($td.containerDefinitions | ForEach-Object { [string]$_.name })
$unexpected = @($containerNames | Where-Object { $_ -ne $AppContainer -and $_ -ne $ScannerContainer })
if ($unexpected.Count -gt 0) { Fail ('unexpected container: ' + ($unexpected -join ',') + ' (this validator knows ses-manager and clamav only)') }
foreach ($name in @($AppContainer, $ScannerContainer)) {
  if (@($containerNames | Where-Object { $_ -eq $name }).Count -ne 1) { Fail "container $name must appear exactly once" }
}
$c = @($td.containerDefinitions | Where-Object { $_.name -eq $AppContainer })[0]
$scanner = @($td.containerDefinitions | Where-Object { $_.name -eq $ScannerContainer })[0]
Ok 'containers ses-manager and clamav, nothing else'

if (-not $c.essential) { Fail 'essential' } else { Ok 'essential=true' }
if (-not $c.readonlyRootFilesystem) { Fail 'readonlyRootFilesystem' } else { Ok 'readonlyRootFilesystem=true' }
if (-not $c.linuxParameters.initProcessEnabled) { Fail 'initProcessEnabled' } else { Ok 'initProcessEnabled=true' }
if ($c.portMappings[0].containerPort -ne 8080) { Fail 'port' } else { Ok 'port 8080' }
# A container hard limit keeps the JVM (MaxRAMPercentage=75) from sizing itself to the whole task.
if ([string]$c.cpu -ne '1024' -or [string]$c.memory -ne '2048') { Fail 'ses-manager container cpu/memory must be 1024/2048 (hard limit)' }
Ok 'ses-manager container cpu/memory 1024/2048 (hard limit)'

# ClamAV sidecar: not essential (uploads fail closed while it is down), sized, health-checked, no app volumes.
if ($scanner.essential -ne $false) { Fail 'clamav must not be essential: the app keeps serving and uploads stay in quarantine while it is down' }
if (-not $scanner.memory -or -not $scanner.cpu) { Fail 'clamav needs container cpu and memory' }
$containerMemory = 0
foreach ($cd in @($td.containerDefinitions)) { $containerMemory += [int]$cd.memory }
if ($containerMemory -gt [int]$td.memory) { Fail 'container memory limits exceed the task memory' }
$scanHealth = ($scanner.healthCheck.command -join ' ')
if (-not $scanHealth -or $scanHealth -match '/actuator/') { Fail 'clamav needs its own health check command (not the app actuator)' }
$scannerMounts = @($scanner.mountPoints | Where-Object { $_.sourceVolume -eq 'uploads' -or $_.sourceVolume -eq $TrustVolume })
if ($scannerMounts.Count -gt 0) { Fail 'clamav must not mount uploads or privacy-trust: files reach it over INSTREAM only' }
if ($scanner.logConfiguration.logDriver -ne 'awslogs') { Fail 'clamav awslogs driver' }
Ok 'clamav sidecar: not essential, sized within the task, own health check, no app volumes, awslogs'

if ($Mode -eq 'Template') {
  if ($c.image -notmatch '@sha256:IMAGE_DIGEST$') { Fail 'IMAGE_DIGEST placeholder' }
  if ($scanner.image -notmatch '@sha256:CLAMAV_IMAGE_DIGEST$') { Fail 'CLAMAV_IMAGE_DIGEST placeholder' }
  Ok 'ECR@IMAGE_DIGEST and ECR@CLAMAV_IMAGE_DIGEST placeholders (not LOCAL_IMAGE_ID)'
} else {
  foreach ($cd in @($c, $scanner)) {
    if ([string]$cd.image -notmatch '@sha256:[0-9a-f]{64}$') { Fail "image of $($cd.name) must be pinned by a registry digest (<registry>/<repository>@sha256:<64 hex>), never a tag or LOCAL_IMAGE_ID" }
  }
  Ok 'images pinned by registry digests'
}

$hc = ($c.healthCheck.command -join ' ')
if ($hc -notmatch 'actuator/health/liveness') { Fail 'container healthCheck must be liveness' } else { Ok 'ECS container healthCheck=liveness' }
if ($hc -match 'actuator/health/readiness') { Fail 'container healthCheck must NOT be readiness' }

if ($Mode -eq 'Template') {
  $alb = $td._offlineAcceptanceNotes.albTargetGroupHealthCheck.path
  if ($alb -ne '/actuator/health/readiness') { Fail 'ALB path must be readiness' } else { Ok 'ALB TG path=/actuator/health/readiness (DEFERRED until real AWS)' }
} else {
  if ($doc.PSObject.Properties.Name -contains '_offlineAcceptanceNotes' -or $td.PSObject.Properties.Name -contains '_offlineAcceptanceNotes') {
    Fail '_offlineAcceptanceNotes must be removed before registering (ECS rejects unknown fields)'
  }
  Info 'the ALB target group health check is not part of a task definition: confirm /actuator/health/readiness with matcher 200 on the target group (forwarded-header-sanitize-notes 3.2)'
}

# Volumes: uploads (read-write) and the NF07 trust documents (read-only), both on EFS with transit encryption.
$volumeAp = @{}
foreach ($volumeName in @('uploads', $TrustVolume)) {
  $volume = @($td.volumes | Where-Object { $_.name -eq $volumeName })[0]
  if (-not $volume -or -not $volume.efsVolumeConfiguration) { Fail "volume $volumeName missing efsVolumeConfiguration" }
  $efs = $volume.efsVolumeConfiguration
  $ap = [string]$efs.authorizationConfig.accessPointId
  if ($Mode -eq 'Template') {
    $expectedAp = if ($volumeName -eq 'uploads') { 'fsap-PLACEHOLDER' } else { 'fsap-TRUST_PLACEHOLDER' }
    if ([string]$efs.fileSystemId -ne 'fs-PLACEHOLDER') { Fail "volume ${volumeName}: EFS fs id placeholder" }
    if ($ap -ne $expectedAp) { Fail "volume ${volumeName}: EFS accessPointId placeholder missing" }
  } else {
    if ([string]$efs.fileSystemId -notmatch '^fs-[0-9a-f]{8,}$') { Fail "volume ${volumeName}: EFS fileSystemId must be a real fs-<hex> id" }
    if ($ap -notmatch '^fsap-[0-9a-f]{8,}$') { Fail "volume ${volumeName}: EFS accessPointId must be a real fsap-<hex> id" }
  }
  if ([string]$efs.transitEncryption -ne 'ENABLED') { Fail "volume ${volumeName}: EFS transitEncryption must be ENABLED" }
  $volumeAp[$volumeName] = $ap
  (Assert-EfsRootDirectoryRule $volumeName $efs) | ForEach-Object { $lines.Add($_) }
}
if ($volumeAp['uploads'] -eq $volumeAp[$TrustVolume]) { Fail 'privacy-trust must use its own EFS access point, not the uploads one' }
$uploadsMount = @($c.mountPoints | Where-Object { $_.sourceVolume -eq 'uploads' })[0]
$trustMount = @($c.mountPoints | Where-Object { $_.sourceVolume -eq $TrustVolume })[0]
if (-not $uploadsMount -or $uploadsMount.containerPath -ne '/app/uploads' -or $uploadsMount.readOnly -eq $true) { Fail 'ses-manager must mount uploads read-write at /app/uploads' }
if (-not $trustMount -or $trustMount.readOnly -ne $true -or [string]$trustMount.containerPath -notmatch '^/[^.]') { Fail 'ses-manager must mount privacy-trust read-only at an absolute path (deploy-runbook 1.6 item 11)' }
$trustPath = ([string]$trustMount.containerPath).TrimEnd('/')
Ok 'uploads (read-write) and privacy-trust (read-only, own access point) on EFS with transit encryption'
if ($Mode -eq 'Template') { Ok 'EFS-ACTUAL=DEFERRED (template static only)' }

$envNames = @($c.environment | ForEach-Object { [string]$_.name })
$secretNames = @($c.secrets | ForEach-Object { [string]$_.name })
$allNames = $envNames + $secretNames

# The same setting twice (also across environment and secrets, or in another spelling Spring binds to the same
# property) leaves the effective value to ordering; fail instead of guessing.
$byKey = @{}
foreach ($name in $allNames) {
  $key = ($name.ToUpperInvariant() -replace '[._-]', '')
  if (-not $byKey.ContainsKey($key)) { $byKey[$key] = New-Object System.Collections.Generic.List[string] }
  $byKey[$key].Add($name)
}
$duplicates = @($byKey.Values | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_ -join '/' })
if ($duplicates.Count -gt 0) { Fail ('settings given more than once: ' + ($duplicates -join ', ')) }
Ok 'each setting given once'

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
$freeeOn = ($freeeEnabled -contains 'true')
if ($freeeOn) {
  $requiredStartup += @('FREEE_CLIENT_ID','FREEE_CLIENT_SECRET','FREEE_REDIRECT_URI','FREEE_TOKEN_ENCRYPTION_KEY')
}
$missing = @($requiredStartup | Where-Object { $allNames -notcontains $_ })
if ($missing.Count -gt 0) { Fail ("prod startup settings missing (deploy-runbook 1.0): " + ($missing -join ',')) }
if ($freeeOn) { Ok 'prod startup settings present (deploy-runbook 1.0 rows 1-30, freee on)' } else { Ok 'prod startup settings present (deploy-runbook 1.0 rows 1-26, freee off)' }

function EnvValues([string]$name) {
  @($c.environment | Where-Object { $_.name -eq $name } | ForEach-Object {
      if ($_.value -is [datetime]) { Fail "$name was read as a date and lost its offset: run with PowerShell 7.5 or later, or Windows PowerShell 5.1" }
      ([string]$_.value).Trim()
    })
}

# deploy-runbook.md section 1.0 operational settings and the 2026-11-18 decisions (section 1.6 items 6, 7, 11).
$requiredOperational = @('FILE_SCANNER_HOST','FILE_SCANNER_PORT','METRICS_SCRAPER_ENABLED','METRICS_SCRAPER_USERNAME','METRICS_SCRAPER_PASSWORD','PRIVACY_TRUST_KMS_ANCHOR_FILE')
$missingOperational = @($requiredOperational | Where-Object { $allNames -notcontains $_ })
if ($missingOperational.Count -gt 0) { Fail ("operational settings missing (deploy-runbook 1.0): " + ($missingOperational -join ',')) }
if (-not ((@(EnvValues 'FILE_SCANNER_HOST') | Where-Object { $_ -eq '127.0.0.1' -or $_ -eq 'localhost' }))) { Fail 'FILE_SCANNER_HOST must be the sidecar on the loopback (127.0.0.1)' }
if (-not ((@(EnvValues 'FILE_SCANNER_PORT')) -contains '3310')) { Fail 'FILE_SCANNER_PORT must be clamd 3310' }
if (-not ((@(EnvValues 'METRICS_SCRAPER_ENABLED') | ForEach-Object { $_.ToLowerInvariant() }) -contains 'true')) { Fail 'METRICS_SCRAPER_ENABLED=true missing: metrics are scraped through the ALB with the scraper account (deploy-runbook 1.6 item 7)' }
Ok 'ClamAV sidecar on 127.0.0.1:3310, metrics scraper on, PRIVACY_TRUST_KMS_ANCHOR_FILE present'

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

# Settings must reach the app as environment variables so that this validator sees them: no system property
# other than the time zone in the JVM option variables, and no guarded property in SPRING_APPLICATION_JSON.
foreach ($name in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')) {
  if ($secretNames -contains $name) { Fail "$name must not come from the secret store: its contents could not be checked" }
  foreach ($value in @(EnvValues $name)) {
    $props = @([regex]::Matches($value, '(?:^|\s)-D([^=\s]+)') | ForEach-Object { $_.Groups[1].Value })
    $other = @($props | Where-Object { $_ -ne 'user.timezone' })
    if ($other.Count -gt 0) { Fail ("$name sets system properties other than user.timezone: " + ($other -join ',') + ' (use environment variables; deploy-runbook 1.0)') }
  }
}
$guarded = @('app.scheduling', 'app.security', 'spring.flyway', 'spring.profiles', 'feature', 'privacy', 'ai',
  'server.forwardheadersstrategy', 'integration.hub', 'cloudsign', 'freee')
function Get-JsonKeys([object]$node, [string]$prefix) {
  if ($node -is [System.Management.Automation.PSCustomObject]) {
    foreach ($p in $node.PSObject.Properties) {
      $key = if ($prefix) { "$prefix.$($p.Name)" } else { $p.Name }
      Get-JsonKeys $p.Value $key
    }
  } elseif ($prefix) {
    $prefix
  }
}
foreach ($json in @(EnvValues 'SPRING_APPLICATION_JSON')) {
  try { $parsed = $json | ConvertFrom-Json } catch { Fail 'SPRING_APPLICATION_JSON is not valid JSON' }
  $keys = @(Get-JsonKeys $parsed '' | ForEach-Object { ($_.ToLowerInvariant() -replace '[_-]', '') })
  $hit = @($keys | Where-Object { $k = $_; @($guarded | Where-Object { $k -eq $_ -or $k.StartsWith("$_.") }).Count -gt 0 })
  if ($hit.Count -gt 0) { Fail ('SPRING_APPLICATION_JSON sets guarded properties: ' + ($hit -join ',') + ' (use environment variables)') }
}
if ($secretNames -contains 'SPRING_APPLICATION_JSON') {
  Info 'SPRING_APPLICATION_JSON comes from the secret store and is not checked here: it may carry compliance.gate.fingerprint-keys only (deploy-runbook 1.0 rows 23-25)'
}
Ok 'no system property or SPRING_APPLICATION_JSON override of the checked settings'

$breakGlass = @(EnvValues 'BREAK_GLASS_USERNAMES')
$anchorFile = @(EnvValues 'PRIVACY_TRUST_KMS_ANCHOR_FILE')
$featureSwitches = @($c.environment | Where-Object {
    $_.name -match '^(FEATURE_NF[0-9A-Z_]*_ENABLED|PRIVACY_RETENTION_ENABLED|PRIVACY_DSAR_ENABLED|INTEGRATION_HUB_(PUBLICAPI|PUBLIC_API|EXTERNALTRANSPORT|EXTERNAL_TRANSPORT)_ENABLED)$'
  } | ForEach-Object { $_.name })
$schedulerValues = @(EnvValues 'APP_SCHEDULING_ENABLED' | ForEach-Object { $_.ToLowerInvariant() })
if ($ExpectedScheduler -eq 'Disabled') {
  if ($schedulerValues.Count -ne 1 -or $schedulerValues[0] -ne 'false') {
    Fail 'APP_SCHEDULING_ENABLED=false expected (-Scheduler Disabled): a missing value turns the scheduler on (deploy-runbook 2 and 4)'
  }
} else {
  if ($schedulerValues.Count -gt 0 -and $schedulerValues[0] -ne 'true') {
    Fail 'APP_SCHEDULING_ENABLED must be true or unset (-Scheduler Enabled): the core jobs would stay stopped (deploy-runbook 2 and 4)'
  }
}
Ok "scheduler as expected ($ExpectedScheduler)"

if ($Phase -eq 'Initial') {
  # deploy-runbook.md sections 4 and 4.2: one task in bootstrap mode, all feature switches unset, NF07 trust as phase 1.
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
      Fail 'SECURITY_BOOTSTRAP_EXPIRES_AT is more than 24 hours ahead: the start refuses it; set it right before the start and validate again (deploy-runbook 4.2)'
    }
  }
  if ($breakGlass.Count -ne 1 -or $breakGlass[0] -ne '') { Fail 'BREAK_GLASS_USERNAMES must be present and empty during bootstrap (deploy-runbook 4.2)' }
  if ($allNames -contains 'SECURITY_BOOTSTRAP_REOPEN_TICKET') { Fail 'SECURITY_BOOTSTRAP_REOPEN_TICKET must not be set for the first bootstrap (deploy-runbook 4.2; 5.7 only)' }
  if ($featureSwitches.Count -gt 0) { Fail ('feature switches must stay unset for the first release (feature-switches 3.1): ' + ($featureSwitches -join ',')) }
  if ($anchorFile.Count -ne 1 -or $anchorFile[0] -ne '') { Fail 'PRIVACY_TRUST_KMS_ANCHOR_FILE must be empty for the first release (NF07 trust as phase 1; deploy-runbook 1.6 item 11)' }
  Ok 'first start: bootstrap mode on, BREAK_GLASS_USERNAMES empty, no feature switch, NF07 trust empty'
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
  if ($anchorFile.Count -ne 1) { Fail 'PRIVACY_TRUST_KMS_ANCHOR_FILE must be given once' }
  if ($anchorFile[0] -ne '' -and (-not $anchorFile[0].StartsWith("$trustPath/") -or $anchorFile[0] -match '(^|/)\.\.(/|$)' -or $anchorFile[0].Length -le ($trustPath.Length + 1))) {
    Fail 'PRIVACY_TRUST_KMS_ANCHOR_FILE must be empty or a file under the read-only privacy-trust mount (deploy-runbook 1.6 item 11)'
  }
  Ok 'after bootstrap: bootstrap settings removed, BREAK_GLASS_USERNAMES has two names, NF07 trust file empty or under the read-only mount'
}

if ($Mode -eq 'Actual') {
  # No placeholder may survive into the definition that is registered.
  $placeholder = 'PLACEHOLDER|ACCOUNT_ID|IMAGE_DIGEST'
  $left = New-Object System.Collections.Generic.List[string]
  foreach ($cd in @($c, $scanner)) {
    foreach ($e in @($cd.environment)) { if ([string]$e.value -match $placeholder) { $left.Add("$($cd.name) environment:$($e.name)") } }
    foreach ($s in @($cd.secrets)) { if ([string]$s.valueFrom -match $placeholder) { $left.Add("$($cd.name) secrets:$($s.name)") } }
    if ([string]$cd.image -match $placeholder) { $left.Add("$($cd.name) image") }
  }
  foreach ($field in @('executionRoleArn', 'taskRoleArn')) { if ([string]$td.$field -match $placeholder) { $left.Add($field) } }
  if ($left.Count -gt 0) { Fail ('placeholders left: ' + ($left -join ',')) }
  Ok 'no placeholder left in environment, secrets, roles or images'

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
$tz = @(EnvValues 'TZ')
if (-not ($tz -contains 'Asia/Tokyo')) { Fail 'TZ=Asia/Tokyo missing in environment (deploy-runbook 1.0 / 4)' }
foreach ($value in @(EnvValues 'JAVA_TOOL_OPTIONS')) {
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
