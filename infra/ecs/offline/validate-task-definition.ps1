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
# mounts the uploads volume read-write and the NF07 trust volume (privacy-trust) read-only. The app writes under
# UPLOAD_BASE_PATH=/app/uploads/current, a symlink on the uploads volume (checked here by value only).
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

# Start-Process joins an argument array with spaces without quoting it, so a path with a space would be split.
# Quote each argument by the Windows command-line rules and pass one string instead.
function ConvertTo-CommandLine([string[]]$arguments) {
  $quoted = foreach ($a in $arguments) {
    if ($a -ne '' -and $a -notmatch '[\s"]') { $a; continue }
    $s = [regex]::Replace($a, '(\\*)"', [System.Text.RegularExpressions.MatchEvaluator]{ param($m) ($m.Groups[1].Value * 2) + '\"' })
    $s = [regex]::Replace($s, '(\\+)$', [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $m.Groups[1].Value * 2 })
    '"' + $s + '"'
  }
  $quoted -join ' '
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
    $p = Start-Process -FilePath $self -ArgumentList (ConvertTo-CommandLine $arguments) -Wait -PassThru -NoNewWindow `
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
foreach ($cd in @($c, $scanner)) {
  if (@($cd.environmentFiles).Count -gt 0 -and $null -ne $cd.environmentFiles) { Fail "$($cd.name) must not use environmentFiles: their settings could not be checked here" }
}
foreach ($field in @('command', 'entryPoint')) {
  if (@($c.$field).Count -gt 0 -and $null -ne $c.$field) { Fail "ses-manager must keep the image $field (an override can pass Spring arguments such as --app.scheduling.enabled=true past this validator)" }
}
foreach ($cd in @($c, $scanner)) {
  if (@($cd.volumesFrom).Count -gt 0 -and $null -ne $cd.volumesFrom) { Fail "$($cd.name) must not use volumesFrom: mounts must be declared in mountPoints (clamav would otherwise reach uploads and privacy-trust)" }
}
Ok 'no environmentFiles or volumesFrom; ses-manager keeps the image command and entryPoint'

# ClamAV sidecar: not essential (uploads fail closed while it is down), sized, health-checked, no app volumes.
if ($scanner.essential -ne $false) { Fail 'clamav must not be essential: the app keeps serving and uploads stay in quarantine while it is down' }
if (-not $scanner.memory -or -not $scanner.cpu) { Fail 'clamav needs container cpu and memory' }
$containerMemory = 0
foreach ($cd in @($td.containerDefinitions)) { $containerMemory += [int]$cd.memory }
if ($containerMemory -gt [int]$td.memory) { Fail 'container memory limits exceed the task memory' }
$scanHealth = ($scanner.healthCheck.command -join ' ')
if (-not $scanHealth -or $scanHealth -match '/actuator/') { Fail 'clamav needs its own health check command (not the app actuator)' }
# The sidecar handles untrusted uploads: read-only root filesystem, only its own bind volumes, no secrets, no app
# settings, no published port (files reach it over INSTREAM on the loopback only).
if ($scanner.readonlyRootFilesystem -ne $true) { Fail 'clamav must run with a read-only root filesystem (its writable paths are the clamav-* bind volumes)' }
foreach ($m in @($scanner.mountPoints)) {
  $vol = @($td.volumes | Where-Object { $_.name -eq $m.sourceVolume })[0]
  if (-not ([string]$m.sourceVolume).StartsWith('clamav-') -or -not $vol -or $vol.efsVolumeConfiguration) {
    Fail 'clamav may mount only its own clamav-* bind volumes (not uploads, privacy-trust, tmp or any EFS volume): files reach it over INSTREAM only'
  }
}
if (@($scanner.secrets).Count -gt 0 -and $null -ne $scanner.secrets) { Fail 'clamav must not receive secrets' }
$appSettingNames = @(@($c.environment) + @($c.secrets) | ForEach-Object { [string]$_.name })
$sharedNames = @(@($scanner.environment) | ForEach-Object { [string]$_.name } | Where-Object { $appSettingNames -contains $_ })
if ($sharedNames.Count -gt 0) { Fail ('clamav must not receive app settings: ' + ($sharedNames -join ',')) }
if (@($scanner.portMappings).Count -gt 0 -and $null -ne $scanner.portMappings) { Fail 'clamav must not publish a port: the app reaches clamd on 127.0.0.1' }
if ($scanner.logConfiguration.logDriver -ne 'awslogs') { Fail 'clamav awslogs driver' }
Ok 'clamav sidecar: not essential, sized within the task, own health check, read-only root filesystem, own bind volumes only, no secrets or app settings, no port, awslogs'

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
  # The file system policy grants by the task role (deploy-runbook 1.6 item 11); without IAM the mount is anonymous.
  if ([string]$efs.authorizationConfig.iam -ne 'ENABLED') { Fail "volume ${volumeName}: EFS authorizationConfig.iam must be ENABLED (deploy-runbook 1.6 item 11)" }
  $volumeAp[$volumeName] = $ap
  (Assert-EfsRootDirectoryRule $volumeName $efs) | ForEach-Object { $lines.Add($_) }
}
if ($volumeAp['uploads'] -eq $volumeAp[$TrustVolume]) { Fail 'privacy-trust must use its own EFS access point, not the uploads one' }
$uploadsMount = @($c.mountPoints | Where-Object { $_.sourceVolume -eq 'uploads' })[0]
$trustMount = @($c.mountPoints | Where-Object { $_.sourceVolume -eq $TrustVolume })[0]
if (-not $uploadsMount -or $uploadsMount.containerPath -ne '/app/uploads' -or $uploadsMount.readOnly -eq $true) { Fail 'ses-manager must mount uploads read-write at /app/uploads' }
if (-not $trustMount -or $trustMount.readOnly -ne $true -or [string]$trustMount.containerPath -notmatch '^/[^.]') { Fail 'ses-manager must mount privacy-trust read-only at an absolute path (deploy-runbook 1.6 item 11)' }
$trustPath = ([string]$trustMount.containerPath).TrimEnd('/')
Ok 'uploads (read-write) and privacy-trust (read-only, own access point) on EFS with transit encryption and IAM authorization'
if ($Mode -eq 'Template') { Ok 'EFS-ACTUAL=DEFERRED (template static only)' }

$envNames = @($c.environment | ForEach-Object { [string]$_.name })
$secretNames = @($c.secrets | ForEach-Object { [string]$_.name })
$allNames = $envNames + $secretNames

# Spring binds every spelling of a property name (APP_FEATURES_NF01_ENABLED, app.features.nf01.enabled, ...) to the same
# property, so names are compared by this key: upper case without '.', '_' and '-'.
function NameKey([string]$name) { ($name.ToUpperInvariant() -replace '[._-]', '') }

# Other characters (app.features[nf01].enabled, ...) could reach a property in a form NameKey does not cover.
$oddNames = @($allNames | Where-Object { $_ -cnotmatch '^[A-Za-z_][A-Za-z0-9_.-]*$' })
if ($oddNames.Count -gt 0) { Fail ('setting names must consist of letters, digits, _ . and - only: ' + ($oddNames -join ',')) }

# The same setting twice (also across environment and secrets, or in another spelling Spring binds to the same
# property) leaves the effective value to ordering; fail instead of guessing.
$byKey = @{}
foreach ($name in $allNames) {
  $key = NameKey $name
  if (-not $byKey.ContainsKey($key)) { $byKey[$key] = New-Object System.Collections.Generic.List[string] }
  $byKey[$key].Add($name)
}
$duplicates = @($byKey.Values | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_ -join '/' })
if ($duplicates.Count -gt 0) { Fail ('settings given more than once: ' + ($duplicates -join ', ')) }
Ok 'each setting given once'

# Feature switches (feature-switches.md section 1, and the NF05 switches of deploy-runbook 1.7) are recognised by their
# key, whatever the spelling, and must use the documented names: the first-start and secrets checks read those names.
$switchKeyPattern = '^(FEATURENF[0-9A-Z]*ENABLED|APPFEATURES[0-9A-Z]*ENABLED|PRIVACYRETENTIONENABLED|PRIVACYDSARENABLED|INTEGRATIONHUB(PUBLICAPI|EXTERNALTRANSPORT)ENABLED)$'
$switchNamePattern = '^(FEATURE_NF[0-9A-Z_]*_ENABLED|PRIVACY_RETENTION_ENABLED|PRIVACY_DSAR_ENABLED|INTEGRATION_HUB_(PUBLICAPI|PUBLIC_API|EXTERNALTRANSPORT|EXTERNAL_TRANSPORT)_ENABLED)$'
$switchNames = @($allNames | Where-Object { (NameKey $_) -match $switchKeyPattern })
$misspelt = @($switchNames | Where-Object { $_ -cnotmatch $switchNamePattern })
if ($misspelt.Count -gt 0) { Fail ('feature switches must use the documented names (FEATURE_NF*_ENABLED, PRIVACY_*_ENABLED, INTEGRATION_HUB_*_ENABLED; feature-switches.md 1): ' + ($misspelt -join ',')) }

# Settings judged by their value must be visible, so they belong in environment, not in the secret store.
$mustBeVisible = @('SPRING_PROFILES_ACTIVE', 'APP_SCHEDULING_ENABLED', 'SECURITY_BOOTSTRAP_ENABLED', 'SECURITY_BOOTSTRAP_EXPIRES_AT',
  'SECURITY_BOOTSTRAP_REOPEN_TICKET', 'BREAK_GLASS_USERNAMES', 'TRUSTED_PROXIES', 'TZ', 'FILE_SCANNER_HOST', 'FILE_SCANNER_PORT',
  'METRICS_SCRAPER_ENABLED', 'METRICS_SCRAPER_USERNAME', 'PRIVACY_TRUST_KMS_ANCHOR_FILE', 'FREEE_ENABLED', 'CLOUDSIGN_ENABLED',
  'LOCAL_LOGIN_ENABLED', 'OIDC_ENABLED', 'BREAK_GLASS_LOGIN_ENABLED', 'OIDC_REQUIRE_MFA_ASSURANCE', 'MFA_ENABLED',
  'MFA_REQUIRED_FOR_BREAK_GLASS', 'ALLOW_UNTRACKED_SESSIONS', 'AI_EXTERNAL_SEND_ENABLED', 'UPLOAD_BASE_PATH', 'SHUTDOWN_TIMEOUT')
$visibleKeys = @($mustBeVisible | ForEach-Object { NameKey $_ })
# BACKUP_QUIESCE_* (deploy-runbook 1.0 rows 31-41) are all judged by value.
$hidden = @($secretNames | Where-Object { $k = NameKey $_; $visibleKeys -contains $k -or $k -match $switchKeyPattern -or $k -match '^FEATURE[0-9A-Z]*ENABLED$' -or $k.StartsWith('BACKUPQUIESCE') })
if ($hidden.Count -gt 0) { Fail ('settings checked by value must be in environment, not secrets: ' + ($hidden -join ',')) }

# deploy-runbook.md section 4: prod alone, and no other profile or configuration source the validator cannot read.
$profiles = @($c.environment | Where-Object { $_.name -eq 'SPRING_PROFILES_ACTIVE' } | ForEach-Object { ([string]$_.value).Trim() })
if ($profiles.Count -ne 1 -or $profiles[0] -cne 'prod') { Fail 'SPRING_PROFILES_ACTIVE must be exactly prod (deploy-runbook 4)' }
$configSources = @($allNames | Where-Object {
    $k = NameKey $_
    $k -eq 'SPRINGPROFILESINCLUDE' -or $k.StartsWith('SPRINGPROFILESGROUP') -or $k.StartsWith('SPRINGCONFIG')
  })
if ($configSources.Count -gt 0) { Fail ('extra profile or configuration sources must not be set: ' + ($configSources -join ',')) }
$flyway = @($allNames | Where-Object { (NameKey $_).StartsWith('SPRINGFLYWAY') })
if ($flyway.Count -gt 0) { Fail ('spring.flyway settings such as SPRING_FLYWAY_ENABLED must not be set (deploy-runbook 4): ' + ($flyway -join ',')) }
Ok 'SPRING_PROFILES_ACTIVE=prod alone; value-checked settings in environment; no extra profile or config source; no Flyway setting'

# Canonical property names bind just like the short names declared in application*.yml and win over the yml
# defaults (APP_SECURITY_BOOTSTRAP_ENABLED reaches the property behind SECURITY_BOOTSTRAP_ENABLED, MANAGEMENT_* opens
# actuator endpoints). Under these property trees only the declared short names are allowed, so every such setting is
# one this validator reads (APP_UPLOAD_BASE_PATH would move the upload root off the current symlink). integration.hub is
# not among them: opening NF05 sets approval lists and the provider mode that have no short name (DG-05 draft
# section 4), and its two switches are caught above in any spelling.
$declared = New-Object System.Collections.Generic.HashSet[string]
foreach ($yml in @('application.yml', 'application-prod.yml')) {
  $ymlPath = Join-Path $RepoRoot "src\main\resources\$yml"
  if (-not (Test-Path $ymlPath)) { Fail "cannot read $yml to learn the declared environment variable names" }
  foreach ($m in [regex]::Matches((Get-Content $ymlPath -Raw), '\$\{([A-Z][A-Z0-9_]*)')) { $null = $declared.Add($m.Groups[1].Value) }
}
$null = $declared.Add('APP_SCHEDULING_ENABLED')
# APPBACKUPQUIESCE: APP_BACKUP_QUIESCE_* / APP_BACKUPQUIESCE_* would bypass the BACKUP_QUIESCE_* checks below.
# SPRINGLIFECYCLE: SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE would replace SHUTDOWN_TIMEOUT behind the stopTimeout check.
$guardedTrees = @('APPFEATURES', 'APPSECURITY', 'APPSCHEDULING', 'APPUPLOAD', 'APPBACKUPQUIESCE', 'MANAGEMENT', 'SPRINGMAIN',
  'SPRINGLIFECYCLE', 'SERVERFORWARDHEADERSSTRATEGY')
$canonical = @($allNames | Where-Object {
    $n = $_
    $k = NameKey $n
    @($guardedTrees | Where-Object { $k.StartsWith($_) }).Count -gt 0 -and -not $declared.Contains($n)
  })
if ($canonical.Count -gt 0) { Fail ('settings under guarded property trees must use the short names declared in application.yml: ' + ($canonical -join ',')) }
Ok 'no canonical-name override of feature switches, security, scheduling, upload, management or forwarded-header settings'

# Secrets and the property each one binds to (application*.yml), so that the canonical spelling of the property
# (SPRING_DATASOURCE_PASSWORD, APP_BATCH_TOKEN_SECRET, ...) is caught in environment as well.
$secretOnly = [ordered]@{
  'DB_PASSWORD' = 'spring.datasource.password'; 'DB_USERNAME' = 'spring.datasource.username'; 'DB_URL' = 'spring.datasource.url';
  'OIDC_CLIENT_SECRET' = 'app.security.oidc.client-secret'; 'BATCH_TOKEN_SECRET' = 'app.batch.token-secret';
  'SESSION_HASH_KEY' = 'app.security.session.hash-key'; 'MFA_ENCRYPTION_KEY' = 'app.security.mfa.encryption-key';
  'PWA_CONTEXT_SECRET' = 'app.pwa.context-secret'; 'BP_BANK_ACCOUNT_ENCRYPTION_KEY' = 'bp.bank-account-encryption-key';
  'CERTIFICATION_NUMBER_CRYPTO_KEYS_V1' = 'certification.number-crypto.keys.v1';
  'COMPLIANCE_GATE_CREDENTIAL_CRYPTO_KEYS_V1' = 'compliance.gate.credential-crypto.keys.v1';
  'COMPLIANCE_GATE_FINGERPRINT_KEYS_DEFAULT_KEYS_V1' = 'compliance.gate.fingerprint-keys.default.keys.v1';
  'FREEE_CLIENT_SECRET' = 'freee.client-secret'; 'FREEE_TOKEN_ENCRYPTION_KEY' = 'freee.token-encryption-key';
  'METRICS_SCRAPER_PASSWORD' = 'app.metrics.scraper.password'
}
$secretKeys = @(foreach ($name in $secretOnly.Keys) { NameKey $name; NameKey $secretOnly[$name] })
$exposed = @($envNames | Where-Object { $secretKeys -contains (NameKey $_) })
if ($exposed.Count -gt 0) { Fail ('secrets in environment (use the secret store): ' + ($exposed -join ',')) }
# Names that look like secrets must not be in environment either, also when the table above does not list them
# (IMPORT_ROW_REFERENCE_HMAC_KEY, CERTIFICATION_NUMBER_CRYPTO_KEYS_V2, SMTP_PASSWORD, ...). Allowed: key versions.
$secretLikeAllowed = @('^[A-Z0-9_]*_CURRENT_KEY_VERSION$')
$secretLike = @($envNames | Where-Object {
    $u = ($_.ToUpperInvariant() -replace '[.-]', '_')
    ($u -match '(_SECRET|_PASSWORD|_KEY|_KEYS|_PEPPER)$' -or $u -match '_KEYS_') -and @($secretLikeAllowed | Where-Object { $u -match $_ }).Count -eq 0
  })
if ($secretLike.Count -gt 0) { Fail ('names that look like secrets must come from the secret store, not environment: ' + ($secretLike -join ',')) }
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
$requiredOperational = @('FILE_SCANNER_HOST','FILE_SCANNER_PORT','METRICS_SCRAPER_ENABLED','METRICS_SCRAPER_USERNAME','METRICS_SCRAPER_PASSWORD','PRIVACY_TRUST_KMS_ANCHOR_FILE','UPLOAD_BASE_PATH')
$missingOperational = @($requiredOperational | Where-Object { $allNames -notcontains $_ })
if ($missingOperational.Count -gt 0) { Fail ("operational settings missing (deploy-runbook 1.0): " + ($missingOperational -join ',')) }
if (-not ((@(EnvValues 'FILE_SCANNER_HOST') | Where-Object { $_ -eq '127.0.0.1' -or $_ -eq 'localhost' }))) { Fail 'FILE_SCANNER_HOST must be the sidecar on the loopback (127.0.0.1)' }
if (-not ((@(EnvValues 'FILE_SCANNER_PORT')) -contains '3310')) { Fail 'FILE_SCANNER_PORT must be clamd 3310' }
if (-not ((@(EnvValues 'METRICS_SCRAPER_ENABLED') | ForEach-Object { $_.ToLowerInvariant() }) -contains 'true')) { Fail 'METRICS_SCRAPER_ENABLED=true missing: metrics are scraped through the ALB with the scraper account (deploy-runbook 1.6 item 7)' }
# Decided 2026-11-18 (deploy-runbook 1.6 item 11 (f)): the app writes under the relative symlink current, so that a
# restore switches versions with one rename. Both modes, exact name and value (no trimming, case-sensitive).
$uploadBase = @($c.environment | Where-Object { [string]$_.name -ceq 'UPLOAD_BASE_PATH' } | ForEach-Object { [string]$_.value })
if ($uploadBase.Count -ne 1 -or $uploadBase[0] -cne '/app/uploads/current') {
  Fail 'UPLOAD_BASE_PATH must be exactly /app/uploads/current (the current symlink under the uploads mount; deploy-runbook 1.6 item 11 (f))'
}
Ok 'ClamAV sidecar on 127.0.0.1:3310, metrics scraper on, PRIVACY_TRUST_KMS_ANCHOR_FILE present, UPLOAD_BASE_PATH=/app/uploads/current'

# deploy-runbook.md section 1.0 "values that must not be set": each of these stops the prod start.
$forbidden = [ordered]@{
  'LOCAL_LOGIN_ENABLED' = 'true'; 'OIDC_ENABLED' = 'false'; 'BREAK_GLASS_LOGIN_ENABLED' = 'false';
  'OIDC_REQUIRE_MFA_ASSURANCE' = 'false'; 'MFA_ENABLED' = 'false'; 'MFA_REQUIRED_FOR_BREAK_GLASS' = 'false';
  'ALLOW_UNTRACKED_SESSIONS' = 'true'; 'AI_EXTERNAL_SEND_ENABLED' = 'true'
}
foreach ($name in $forbidden.Keys) {
  if ((EnvValues $name | ForEach-Object { $_.ToLowerInvariant() }) -contains $forbidden[$name]) { Fail "$name=$($forbidden[$name]) stops the prod start (deploy-runbook 1.0)" }
}
if (((EnvValues 'CLOUDSIGN_ENABLED') | ForEach-Object { $_.ToLowerInvariant() }) -contains 'true' -and $allNames -notcontains 'CLOUDSIGN_CLIENT_ID') {
  Fail 'CLOUDSIGN_ENABLED=true without CLOUDSIGN_CLIENT_ID stops the prod start (deploy-runbook 1.0)'
}
Ok 'no value that deploy-runbook 1.0 forbids'

# Backup write quiesce (deploy-runbook 1.0 rows 31-41; .kiro/specs/backup-write-quiesce design 12.1-12.3). The app checks the
# same ranges when it binds them and refuses BACKUP_QUIESCE_ENABLED=false without a change ticket (U-7); checked here so that
# the definition fails before it is registered. Durations must be written as <number><unit> (ms, s, m, h, d): Spring would
# also take ISO-8601 and a bare number (milliseconds), which this validator refuses rather than guess.
$quiesceNames = @('BACKUP_QUIESCE_ENABLED', 'BACKUP_QUIESCE_DISABLED_TICKET', 'BACKUP_QUIESCE_POLL_INTERVAL',
  'BACKUP_QUIESCE_HEARTBEAT_INTERVAL', 'BACKUP_QUIESCE_FENCE_AFTER', 'BACKUP_QUIESCE_DRAIN_TIMEOUT_CHECKPOINT',
  'BACKUP_QUIESCE_DRAIN_TIMEOUT_FULL', 'BACKUP_QUIESCE_MAX_HOLD_CHECKPOINT', 'BACKUP_QUIESCE_MAX_HOLD_FULL',
  'BACKUP_QUIESCE_STARTUP_WAIT_MAX', 'BACKUP_QUIESCE_RETENTION')
$quiesceOdd = @($allNames | Where-Object { (NameKey $_).StartsWith('BACKUPQUIESCE') -and $quiesceNames -cnotcontains $_ })
if ($quiesceOdd.Count -gt 0) { Fail ('backup quiesce settings must use the documented names (deploy-runbook 1.0 rows 31-41): ' + ($quiesceOdd -join ',')) }
$quiesceEnabled = @(EnvValues 'BACKUP_QUIESCE_ENABLED')
$quiesceTicket = @(EnvValues 'BACKUP_QUIESCE_DISABLED_TICKET' | Where-Object { $_ -ne '' })
if ($quiesceEnabled.Count -ne 1) { Fail 'BACKUP_QUIESCE_ENABLED must be given once in environment: true, or false only with BACKUP_QUIESCE_DISABLED_TICKET (deploy-runbook 1.0 row 31)' }
if ($Mode -eq 'Template') {
  if ($quiesceEnabled[0] -cne 'true' -or $quiesceTicket.Count -gt 0) {
    Fail 'the template must state BACKUP_QUIESCE_ENABLED=true without BACKUP_QUIESCE_DISABLED_TICKET (deploy-runbook 1.0 row 31)'
  }
} elseif ($quiesceEnabled[0] -ceq 'true') {
  if ($quiesceTicket.Count -gt 0) { Fail 'BACKUP_QUIESCE_DISABLED_TICKET belongs only to BACKUP_QUIESCE_ENABLED=false: remove it when the emergency stop ends (deploy-runbook 1.0 row 32)' }
} elseif ($quiesceEnabled[0] -ceq 'false') {
  if ($quiesceTicket.Count -ne 1 -or $quiesceTicket[0] -cnotmatch '^[A-Z][A-Z0-9]+-[0-9]+$') {
    Fail 'BACKUP_QUIESCE_ENABLED=false needs BACKUP_QUIESCE_DISABLED_TICKET in the change-ticket form ^[A-Z][A-Z0-9]+-[0-9]+$ such as CHG-1234 (the emergency stop; deploy-runbook 1.0 row 32)'
  }
  Info 'BACKUP_QUIESCE_ENABLED=false with a change ticket: the emergency stop is in effect, every backup fails and the RPO alert fires until it is set back to true (deploy-runbook 1.0)'
} else {
  Fail 'BACKUP_QUIESCE_ENABLED must be true or false (deploy-runbook 1.0 row 31)'
}
function DurationSeconds([string]$name, [string]$default) {
  $given = @(EnvValues $name)
  $value = if ($given.Count -gt 0) { $given[0] } else { $default }
  $m = [regex]::Match($value, '^([0-9]{1,9})(ms|s|m|h|d)$')
  if (-not $m.Success) { Fail "$name must be a whole number with a unit ms, s, m, h or d, such as 300s (deploy-runbook 1.0)" }
  $n = [double]$m.Groups[1].Value
  switch -CaseSensitive ($m.Groups[2].Value) {
    'ms' { return $n / 1000 }
    's' { return $n }
    'm' { return $n * 60 }
    'h' { return $n * 3600 }
    'd' { return $n * 86400 }
  }
}
$poll = DurationSeconds 'BACKUP_QUIESCE_POLL_INTERVAL' '1s'
$heartbeat = DurationSeconds 'BACKUP_QUIESCE_HEARTBEAT_INTERVAL' '5s'
$fence = DurationSeconds 'BACKUP_QUIESCE_FENCE_AFTER' '20s'
$drainCheckpoint = DurationSeconds 'BACKUP_QUIESCE_DRAIN_TIMEOUT_CHECKPOINT' '30s'
$drainFull = DurationSeconds 'BACKUP_QUIESCE_DRAIN_TIMEOUT_FULL' '600s'
$holdCheckpoint = DurationSeconds 'BACKUP_QUIESCE_MAX_HOLD_CHECKPOINT' '300s'
$holdFull = DurationSeconds 'BACKUP_QUIESCE_MAX_HOLD_FULL' '3600s'
$startupWait = DurationSeconds 'BACKUP_QUIESCE_STARTUP_WAIT_MAX' '60s'
$retention = DurationSeconds 'BACKUP_QUIESCE_RETENTION' '30d'
$shutdown = DurationSeconds 'SHUTDOWN_TIMEOUT' '30s'
if (-not ($poll -ge 0.2 -and $poll -lt $heartbeat -and $heartbeat -lt $fence -and $fence -le 30)) {
  Fail 'BACKUP_QUIESCE_POLL_INTERVAL must be at least 200ms and shorter than BACKUP_QUIESCE_HEARTBEAT_INTERVAL, which must be shorter than BACKUP_QUIESCE_FENCE_AFTER, at most 30s (deploy-runbook 1.0 rows 33-35)'
}
if (-not (($fence - $heartbeat) -gt 13)) {
  Fail 'BACKUP_QUIESCE_FENCE_AFTER minus BACKUP_QUIESCE_HEARTBEAT_INTERVAL must be longer than 13 seconds, the longest a control statement can block (deploy-runbook 1.0 row 35)'
}
if (-not ($holdCheckpoint -ge 30 -and $holdCheckpoint -le 900 -and $drainCheckpoint -gt 0 -and $drainCheckpoint -lt $holdCheckpoint)) {
  Fail 'BACKUP_QUIESCE_MAX_HOLD_CHECKPOINT must be 30s to 900s and BACKUP_QUIESCE_DRAIN_TIMEOUT_CHECKPOINT positive and shorter (deploy-runbook 1.0 rows 36 and 38)'
}
if (-not ($holdFull -ge 60 -and $holdFull -le 14400 -and $drainFull -gt 0 -and $drainFull -lt $holdFull)) {
  Fail 'BACKUP_QUIESCE_MAX_HOLD_FULL must be 60s to 4h and BACKUP_QUIESCE_DRAIN_TIMEOUT_FULL positive and shorter (deploy-runbook 1.0 rows 37 and 39)'
}
if (-not ($startupWait -ge 10 -and $startupWait -le 120)) { Fail 'BACKUP_QUIESCE_STARTUP_WAIT_MAX must be 10s to 120s (deploy-runbook 1.0 row 40)' }
if (-not ($retention -ge 86400 -and $retention -le 31536000)) { Fail 'BACKUP_QUIESCE_RETENTION must be 1d to 365d (deploy-runbook 1.0 row 41)' }
# A start that waits for a running quiesce must not be killed by the container health check (design 12.3).
$startPeriod = 0
if ($null -ne $c.healthCheck.startPeriod) { $startPeriod = [double]$c.healthCheck.startPeriod }
if ($startPeriod -lt ($startupWait + 120)) {
  Fail 'ses-manager healthCheck startPeriod must be at least BACKUP_QUIESCE_STARTUP_WAIT_MAX + 120 seconds (deploy-runbook 1.0 row 40)'
}
# ECS stops the container after stopTimeout: graceful shutdown (SHUTDOWN_TIMEOUT), the task executor (30 s) and a margin
# for the 10-second drain before the instance row is set to STOPPED (design 12.3). Fargate allows at most 120 seconds.
$stopTimeout = 0
if ($null -ne $c.stopTimeout) { $stopTimeout = [double]$c.stopTimeout }
if ($stopTimeout -lt ($shutdown + 60)) {
  Fail 'ses-manager stopTimeout must be at least SHUTDOWN_TIMEOUT + 60 seconds (90 with the default 30s; at most 120 on Fargate, so keep SHUTDOWN_TIMEOUT at 60s or less; deploy-runbook 1.0)'
}
Ok 'backup write quiesce: BACKUP_QUIESCE_ENABLED as required, BACKUP_QUIESCE_* within range, startPeriod >= startup wait + 120 s, stopTimeout >= SHUTDOWN_TIMEOUT + 60 s'

# Settings must reach the app as environment variables so that this validator sees them: no system property
# other than the time zone in the JVM option variables, and no guarded property in SPRING_APPLICATION_JSON.
foreach ($name in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')) {
  if ($secretNames -contains $name) { Fail "$name must not come from the secret store: its contents could not be checked" }
  foreach ($value in @(EnvValues $name)) {
    if ($value -match '(^|\s)["'']?@') { Fail "$name must not read an @argfile: its contents could not be checked (deploy-runbook 1.0)" }
    if ($value -match '-XX:(VMOptionsFile|Flags)=') { Fail "$name must not read JVM options from a file (-XX:VMOptionsFile / -XX:Flags): its contents could not be checked (deploy-runbook 1.0)" }
    $props = @([regex]::Matches($value, '(?:^|\s)["'']?-D([^=\s"'']+)') | ForEach-Object { $_.Groups[1].Value })
    $other = @($props | Where-Object { $_ -ne 'user.timezone' })
    if ($other.Count -gt 0) { Fail ("$name sets system properties other than user.timezone: " + ($other -join ',') + ' (use environment variables; deploy-runbook 1.0)') }
  }
}
# Spring also reads SPRING_APPLICATION_JSON as spring.application.json or spring_application_json; only the name read
# below is allowed. From the secret store its contents could not be checked. compliance.* covers the fingerprint keys
# (deploy-runbook 1.0 rows 23-25), which go by their own variables so that the key stays in the secret store.
$jsonNames = @($allNames | Where-Object { (NameKey $_) -eq 'SPRINGAPPLICATIONJSON' })
$jsonOther = @($jsonNames | Where-Object { $_ -cne 'SPRING_APPLICATION_JSON' })
if ($jsonOther.Count -gt 0) { Fail ('SPRING_APPLICATION_JSON must be spelt exactly so that it is checked: ' + ($jsonOther -join ',')) }
if ($secretNames -contains 'SPRING_APPLICATION_JSON') {
  Fail 'SPRING_APPLICATION_JSON must not come from the secret store: its contents could not be checked; pass the fingerprint keys by their environment variable names (deploy-runbook 1.0 rows 23-25)'
}
$guarded = @('app.scheduling', 'app.security', 'spring.flyway', 'spring.profiles', 'feature', 'privacy', 'ai',
  'server.forwardheadersstrategy', 'integration.hub', 'cloudsign', 'freee', 'management', 'spring.config', 'spring.main',
  'compliance', 'app.upload', 'app.backupquiesce', 'spring.lifecycle')
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
Ok 'no system property or SPRING_APPLICATION_JSON override of the checked settings'

$breakGlass = @(EnvValues 'BREAK_GLASS_USERNAMES')
$anchorFile = @(EnvValues 'PRIVACY_TRUST_KMS_ANCHOR_FILE')
$featureSwitches = $switchNames
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
  # deploy-runbook.md sections 4 and 4.2: one task in bootstrap mode, all feature switches unset, NF07 trust file empty.
  # The switches of the first release go on from a steady start after section 5.5 (deploy-runbook 1.7, feature-switches 3.1).
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
  if ($featureSwitches.Count -gt 0) { Fail ('feature switches must stay unset for the initial bootstrap start; set them from a steady start after deploy-runbook 5.5 (feature-switches 3.1): ' + ($featureSwitches -join ',')) }
  if ($anchorFile.Count -ne 1 -or $anchorFile[0] -ne '') { Fail 'PRIVACY_TRUST_KMS_ANCHOR_FILE must be empty for the initial bootstrap start; set it from a steady start (deploy-runbook 1.6 item 11)' }
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
