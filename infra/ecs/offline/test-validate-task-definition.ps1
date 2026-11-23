#Requires -Version 5.1
# Rule-by-rule positive and negative cases for validate-task-definition.ps1 - no AWS API, no Docker.
#
# Each case derives a task definition from the repository template, writes it to a new directory under the system
# temp directory and runs the validator on it in a child process of the same PowerShell (pwsh 7.5 or later, or
# Windows PowerShell 5.1). A positive case must exit 0; a negative case must exit non-zero with a FAIL line in the
# validator evidence that matches the expected message. The validator's inline EFS self-test is skipped in the children
# (the last negative case covers the same rule). Nothing is written inside the repository.
#
# CI does not run this. Run it after changing the validator, under both hosts (deploy-runbook section 4):
#   pwsh -NoProfile -File infra/ecs/offline/test-validate-task-definition.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File infra/ecs/offline/test-validate-task-definition.ps1
# -Filter <text> runs only the cases whose name contains the text.
param([string]$Filter = '')

$ErrorActionPreference = 'Stop'
$Validator = (Resolve-Path (Join-Path $PSScriptRoot 'validate-task-definition.ps1')).Path
$TemplatePath = (Resolve-Path (Join-Path $PSScriptRoot '..\task-definition.prod.json')).Path
$Self = (Get-Process -Id $PID).Path
$Utf8 = New-Object System.Text.UTF8Encoding $false
$Work = Join-Path ([System.IO.Path]::GetTempPath()) ('ses-td-selftest-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $Work | Out-Null

# Start-Process joins an argument array with spaces without quoting it, so a path with a space would be split.
# Quote each argument by the Windows command-line rules and pass one string instead (same as the validator).
function ConvertTo-CommandLine([string[]]$arguments) {
  $quoted = foreach ($a in $arguments) {
    if ($a -ne '' -and $a -notmatch '[\s"]') { $a; continue }
    $s = [regex]::Replace($a, '(\\*)"', [System.Text.RegularExpressions.MatchEvaluator]{ param($m) ($m.Groups[1].Value * 2) + '\"' })
    $s = [regex]::Replace($s, '(\\+)$', [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $m.Groups[1].Value * 2 })
    '"' + $s + '"'
  }
  $quoted -join ' '
}

function Read-Template {
  $raw = [System.IO.File]::ReadAllText($TemplatePath)
  if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { return ($raw | ConvertFrom-Json -DateKind String) }
  return ($raw | ConvertFrom-Json)
}
function App($td) { @($td.containerDefinitions | Where-Object { $_.name -eq 'ses-manager' })[0] }
function Scanner($td) { @($td.containerDefinitions | Where-Object { $_.name -eq 'clamav' })[0] }
function Vol($td, [string]$name) { @($td.volumes | Where-Object { $_.name -eq $name })[0] }
function Set-Prop($obj, [string]$name, $value) { $obj | Add-Member -NotePropertyName $name -NotePropertyValue $value -Force }
function Set-Env($cd, [string]$name, [string]$value) {
  $hit = @($cd.environment | Where-Object { $_.name -ceq $name })
  if ($hit.Count -gt 0) { $hit[0].value = $value; return }
  Set-Prop $cd 'environment' (@($cd.environment | Where-Object { $_ }) + [pscustomobject]@{ name = $name; value = $value })
}
function Remove-Env($cd, [string]$name) { Set-Prop $cd 'environment' @($cd.environment | Where-Object { $_.name -cne $name }) }
function Add-Secret($cd, [string]$name) {
  $arn = "arn:aws:secretsmanager:ap-northeast-1:123456789012:secret:ses/prod/app:${name}::"
  Set-Prop $cd 'secrets' (@($cd.secrets | Where-Object { $_ }) + [pscustomobject]@{ name = $name; valueFrom = $arn })
}

# The template with real-looking values: passes -Mode Actual -Phase Initial.
function New-Initial {
  $td = Read-Template
  $null = $td.PSObject.Properties.Remove('_offlineAcceptanceNotes')
  $td.executionRoleArn = 'arn:aws:iam::123456789012:role/ecsTaskExecutionRole'
  $td.taskRoleArn = 'arn:aws:iam::123456789012:role/ecsTaskRole'
  $app = App $td
  $scan = Scanner $td
  $app.image = '123456789012.dkr.ecr.ap-northeast-1.amazonaws.com/ses-manager-pro@sha256:' + ('a' * 64)
  $scan.image = '123456789012.dkr.ecr.ap-northeast-1.amazonaws.com/ses-clamav@sha256:' + ('b' * 64)
  Set-Env $app 'TRUSTED_PROXIES' '10.0.0.0/16'
  foreach ($n in @('OIDC_ISSUER_URI', 'OIDC_AUTHORIZATION_URI', 'OIDC_TOKEN_URI', 'OIDC_JWK_SET_URI', 'OIDC_USER_INFO_URI')) {
    Set-Env $app $n ('https://idp.example.invalid/' + $n.ToLowerInvariant())
  }
  Set-Env $app 'METRICS_SCRAPER_USERNAME' 'prometheus-scraper'
  $expires = [DateTimeOffset]::Now.AddHours(2).ToString("yyyy-MM-dd'T'HH:mm:sszzz", [System.Globalization.CultureInfo]::InvariantCulture)
  Set-Env $app 'SECURITY_BOOTSTRAP_EXPIRES_AT' $expires
  foreach ($s in @($app.secrets)) { $s.valueFrom = ([string]$s.valueFrom).Replace('ACCOUNT_ID', '123456789012') }
  $uploads = (Vol $td 'uploads').efsVolumeConfiguration
  $trust = (Vol $td 'privacy-trust').efsVolumeConfiguration
  $uploads.fileSystemId = 'fs-0123456789abcdef0'
  $trust.fileSystemId = 'fs-0123456789abcdef0'
  $uploads.authorizationConfig.accessPointId = 'fsap-0123456789abcdef0'
  $trust.authorizationConfig.accessPointId = 'fsap-0fedcba9876543210'
  return $td
}

# After deploy-runbook section 5.5: bootstrap settings removed, two break-glass names, scheduler as given.
function New-Steady([string]$scheduler) {
  $td = New-Initial
  $app = App $td
  Remove-Env $app 'SECURITY_BOOTSTRAP_ENABLED'
  Remove-Env $app 'SECURITY_BOOTSTRAP_EXPIRES_AT'
  Set-Env $app 'BREAK_GLASS_USERNAMES' 'bg-admin1,bg-admin2'
  Set-Env $app 'APP_SCHEDULING_ENABLED' $scheduler
  return $td
}

$Initial = @('-Mode', 'Actual', '-Phase', 'Initial')
$SteadyOn = @('-Mode', 'Actual', '-Phase', 'Steady', '-Scheduler', 'Enabled')
$SteadyOff = @('-Mode', 'Actual', '-Phase', 'Steady', '-Scheduler', 'Disabled')
$Results = New-Object System.Collections.Generic.List[string]
$script:Mismatch = 0
$script:Index = 0

# $Expect: 'PASS', or a regex that a FAIL line of the validator evidence must match.
function Case([string]$Name, [string[]]$ValidatorArgs, [string]$Expect, [scriptblock]$Build) {
  if ($Filter -and $Name -notlike "*$Filter*") { return }
  $script:Index++
  $dir = Join-Path $Work ('case-' + $script:Index)
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $td = & $Build
  if ($td -is [string]) {
    $file = $td
  } else {
    $file = Join-Path $dir 'task-definition.json'
    [System.IO.File]::WriteAllText($file, ($td | ConvertTo-Json -Depth 32), $Utf8)
  }
  $evidence = Join-Path $dir 'ev'
  $arguments = @('-NoProfile')
  if ($PSVersionTable.PSEdition -eq 'Desktop') { $arguments += @('-ExecutionPolicy', 'Bypass') }
  $arguments += @('-File', $Validator) + $ValidatorArgs + @('-TaskDefinitionPath', $file, '-EvidenceRoot', $evidence, '-SkipInlineNegativeSelfTest')
  $out = Join-Path $dir 'out.txt'
  $err = Join-Path $dir 'err.txt'
  $p = Start-Process -FilePath $Self -ArgumentList (ConvertTo-CommandLine $arguments) -Wait -PassThru -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError $err
  $stdout = if (Test-Path $out) { [System.IO.File]::ReadAllText($out) } else { '' }
  $evidenceFile = Join-Path $evidence 'task-definition-validation.txt'
  $failLines = @(if (Test-Path $evidenceFile) { [System.IO.File]::ReadAllLines($evidenceFile) | Where-Object { $_.StartsWith('FAIL ') } })
  if ($Expect -eq 'PASS') {
    $ok = ($p.ExitCode -eq 0 -and $failLines.Count -eq 0 -and $stdout -match 'task-definition static validation PASS')
    $seen = if ($failLines.Count -gt 0) { $failLines[0] } else { "exit=$($p.ExitCode)" }
  } else {
    $ok = ($p.ExitCode -ne 0 -and $stdout -notmatch 'task-definition static validation PASS' -and @($failLines | Where-Object { $_ -match $Expect }).Count -gt 0)
    $seen = if ($failLines.Count -gt 0) { $failLines[0] } else { "exit=$($p.ExitCode), no FAIL line" }
  }
  if (-not $ok) { $script:Mismatch++ }
  $mark = if ($ok) { 'OK ' } else { 'NG ' }
  $kind = if ($Expect -eq 'PASS') { 'positive' } else { 'negative' }
  $Results.Add("$mark [$kind] $Name -> $seen")
}

try {
  # ---- positive cases
  Case 'template as is (-Mode Template)' @() 'PASS' { $TemplatePath }
  Case 'actual, first start' $Initial 'PASS' { New-Initial }
  Case 'actual, describe-task-definition wrapper' $Initial 'PASS' { [pscustomobject]@{ taskDefinition = (New-Initial) } }
  Case 'steady, scheduler enabled' $SteadyOn 'PASS' { New-Steady 'true' }
  Case 'steady, scheduler enabled and unset' $SteadyOn 'PASS' { $td = New-Steady 'true'; Remove-Env (App $td) 'APP_SCHEDULING_ENABLED'; $td }
  Case 'steady, scheduler disabled (drain revision)' $SteadyOff 'PASS' { New-Steady 'false' }
  Case 'steady, feature switches by their documented names' $SteadyOn 'PASS' {
    $td = New-Steady 'true'; Set-Env (App $td) 'FEATURE_NF01_ENABLED' 'true'; Set-Env (App $td) 'PRIVACY_RETENTION_ENABLED' 'true'; $td }
  Case 'steady, NF05 opened with settings that have no short name (DG-05 draft 4.1)' $SteadyOn 'PASS' {
    $td = New-Steady 'true'; $app = App $td
    Set-Env $app 'INTEGRATION_HUB_PUBLIC_API_ENABLED' 'true'
    Set-Env $app 'INTEGRATION_HUB_PUBLIC_API_APPROVAL_ALLOWED_CLIENT_CIDRS' '10.1.0.0/24'
    Set-Env $app 'INTEGRATION_HUB_SECURITY_TRUSTED_PROXIES' '10.0.0.0/16'
    Add-Secret $app 'INTEGRATION_HUB_PUBLIC_ID_KEY'
    Add-Secret $app 'INTEGRATION_HUB_CRYPTO_KEYS_V1'
    $td }
  Case 'actual, SPRING_APPLICATION_JSON in environment without checked settings' $Initial 'PASS' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_APPLICATION_JSON' '{"logging":{"level":{"root":"INFO"}}}'; $td }
  Case 'actual, secret-like names in secrets and key versions in environment' $SteadyOn 'PASS' {
    $td = New-Steady 'true'; $app = App $td
    Add-Secret $app 'IMPORT_ROW_REFERENCE_HMAC_KEY'; Add-Secret $app 'CERTIFICATION_NUMBER_CRYPTO_KEYS_V2'; Add-Secret $app 'SMTP_PASSWORD'
    Set-Env $app 'MFA_CURRENT_KEY_VERSION' 'v1'; Set-Env $app 'INTEGRATION_HUB_CRYPTO_CURRENT_KEY_VERSION' 'v1'; $td }
  Case 'actual, privacy-trust on its own EFS file system (deploy-runbook 1.6 item 11 (e))' $Initial 'PASS' {
    $td = New-Initial; (Vol $td 'privacy-trust').efsVolumeConfiguration.fileSystemId = 'fs-0fedcba9876543210'; $td }
  Case 'actual, JAVA_TOOL_OPTIONS with the time zone and a heap option' $Initial 'PASS' {
    $td = New-Initial; Set-Env (App $td) 'JAVA_TOOL_OPTIONS' '-Duser.timezone=Asia/Tokyo -XX:MaxRAMPercentage=75'; $td }

  # ---- negative cases added for the e8dcf83c review (P3-2 to P3-6) and the EFS IAM rule
  Case 'profile PROD (upper case)' $Initial 'SPRING_PROFILES_ACTIVE must be exactly prod' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_PROFILES_ACTIVE' 'PROD'; $td }
  Case 'profile Prod (mixed case)' $Initial 'SPRING_PROFILES_ACTIVE must be exactly prod' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_PROFILES_ACTIVE' 'Prod'; $td }
  Case 'APP_FEATURES_NF01_ENABLED at the first start' $Initial 'feature switches must use the documented names' {
    $td = New-Initial; Set-Env (App $td) 'APP_FEATURES_NF01_ENABLED' 'true'; $td }
  Case 'APP_FEATURES_NF01_ENABLED after bootstrap' $SteadyOn 'feature switches must use the documented names' {
    $td = New-Steady 'true'; Set-Env (App $td) 'APP_FEATURES_NF01_ENABLED' 'true'; $td }
  Case 'app.features.nf02-customer-portal.enabled at the first start' $Initial 'feature switches must use the documented names' {
    $td = New-Initial; Set-Env (App $td) 'app.features.nf02-customer-portal.enabled' 'true'; $td }
  Case 'privacy.retention.enabled at the first start' $Initial 'feature switches must use the documented names' {
    $td = New-Initial; Set-Env (App $td) 'privacy.retention.enabled' 'true'; $td }
  Case 'integration.hub.public-api.enabled at the first start' $Initial 'feature switches must use the documented names' {
    $td = New-Initial; Set-Env (App $td) 'integration.hub.public-api.enabled' 'true'; $td }
  Case 'INTEGRATION_HUB_PUBLICAPI_ENABLED at the first start' $Initial 'feature switches must stay unset for the initial bootstrap start' {
    $td = New-Initial; Set-Env (App $td) 'INTEGRATION_HUB_PUBLICAPI_ENABLED' 'false'; $td }
  Case 'FEATURE_NF01_ENABLED from the secret store' $SteadyOn 'settings checked by value must be in environment' {
    $td = New-Steady 'true'; Add-Secret (App $td) 'FEATURE_NF01_ENABLED'; $td }
  Case 'app_scheduling_enabled from the secret store' $SteadyOn 'settings checked by value must be in environment' {
    $td = New-Steady 'true'; Remove-Env (App $td) 'APP_SCHEDULING_ENABLED'; Add-Secret (App $td) 'app_scheduling_enabled'; $td }
  Case 'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE' $SteadyOn 'guarded property trees must use the short names' {
    $td = New-Steady 'true'; Set-Env (App $td) 'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE' '*'; $td }
  Case 'management.endpoint.env.show-values' $Initial 'guarded property trees must use the short names' {
    $td = New-Initial; Set-Env (App $td) 'management.endpoint.env.show-values' 'ALWAYS'; $td }
  Case 'APP_SECURITY_BOOTSTRAP_ENABLED after bootstrap' $SteadyOn 'guarded property trees must use the short names' {
    $td = New-Steady 'true'; Set-Env (App $td) 'APP_SECURITY_BOOTSTRAP_ENABLED' 'true'; $td }
  Case 'scheduler spelt app.scheduling.enabled' $SteadyOn 'guarded property trees must use the short names' {
    $td = New-Steady 'true'; Remove-Env (App $td) 'APP_SCHEDULING_ENABLED'; Set-Env (App $td) 'app.scheduling.enabled' 'true'; $td }
  Case 'SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING' $Initial 'guarded property trees must use the short names' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING' 'true'; $td }
  Case 'SPRING_FLYWAY_ENABLED' $Initial 'spring.flyway settings such as SPRING_FLYWAY_ENABLED must not be set' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_FLYWAY_ENABLED' 'true'; $td }
  Case 'spring.flyway.baseline-on-migrate' $Initial 'spring.flyway settings such as SPRING_FLYWAY_ENABLED must not be set' {
    $td = New-Initial; Set-Env (App $td) 'spring.flyway.baseline-on-migrate' 'true'; $td }
  Case 'clamav volumesFrom ses-manager' $Initial 'clamav must not use volumesFrom' {
    $td = New-Initial; Set-Prop (Scanner $td) 'volumesFrom' @([pscustomobject]@{ sourceContainer = 'ses-manager'; readOnly = $true }); $td }
  Case 'ses-manager volumesFrom clamav' $Initial 'ses-manager must not use volumesFrom' {
    $td = New-Initial; Set-Prop (App $td) 'volumesFrom' @([pscustomobject]@{ sourceContainer = 'clamav' }); $td }
  Case 'SPRING_APPLICATION_JSON from the secret store' $Initial 'SPRING_APPLICATION_JSON must not come from the secret store' {
    $td = New-Initial; Add-Secret (App $td) 'SPRING_APPLICATION_JSON'; $td }
  Case 'spring_application_json in environment' $Initial 'SPRING_APPLICATION_JSON must be spelt exactly' {
    $td = New-Initial; Set-Env (App $td) 'spring_application_json' '{"management":{"endpoints":{"web":{"exposure":{"include":"*"}}}}}'; $td }
  Case 'fingerprint key in the environment SPRING_APPLICATION_JSON' $Initial 'SPRING_APPLICATION_JSON sets guarded properties' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_APPLICATION_JSON' '{"compliance":{"gate":{"fingerprint-keys":{"default":{"keys":{"v2":"x"}}}}}}'; $td }
  Case 'JAVA_TOOL_OPTIONS -XX:VMOptionsFile' $Initial 'must not read JVM options from a file' {
    $td = New-Initial; Set-Env (App $td) 'JAVA_TOOL_OPTIONS' '-Duser.timezone=Asia/Tokyo -XX:VMOptionsFile=/tmp/jvm.options'; $td }
  Case 'JDK_JAVA_OPTIONS -XX:Flags' $Initial 'must not read JVM options from a file' {
    $td = New-Initial; Set-Env (App $td) 'JDK_JAVA_OPTIONS' '-XX:Flags=/tmp/.hotspotrc'; $td }
  Case 'uploads EFS without IAM authorization' $Initial 'authorizationConfig.iam must be ENABLED' {
    $td = New-Initial; (Vol $td 'uploads').efsVolumeConfiguration.authorizationConfig.iam = 'DISABLED'; $td }
  Case 'privacy-trust EFS without the iam field' $Initial 'authorizationConfig.iam must be ENABLED' {
    $td = New-Initial; $null = (Vol $td 'privacy-trust').efsVolumeConfiguration.authorizationConfig.PSObject.Properties.Remove('iam'); $td }

  # ---- negative cases added for the 12365645 review (P3-3, P3-4)
  Case 'bracket form app.features[nf01].enabled' $Initial 'setting names must consist of letters, digits' {
    $td = New-Initial; Set-Env (App $td) 'app.features[nf01].enabled' 'true'; $td }
  Case 'bracket form in a secret name' $SteadyOn 'setting names must consist of letters, digits' {
    $td = New-Steady 'true'; Add-Secret (App $td) 'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE[0]'; $td }
  Case 'SPRING_DATASOURCE_PASSWORD in environment' $Initial 'secrets in environment \(use the secret store\)' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_DATASOURCE_PASSWORD' 'plain-text'; $td }
  Case 'APP_BATCH_TOKEN_SECRET in environment' $Initial 'secrets in environment \(use the secret store\)' {
    $td = New-Initial; Set-Env (App $td) 'APP_BATCH_TOKEN_SECRET' 'plain-text'; $td }
  Case 'DB_PASSWORD in environment' $Initial 'secrets in environment \(use the secret store\)' {
    $td = New-Initial; Set-Env (App $td) 'DB_PASSWORD' 'plain-text'; $app = App $td
    Set-Prop $app 'secrets' @($app.secrets | Where-Object { $_.name -ne 'DB_PASSWORD' }); $td }

  # ---- negative cases added for the a232cc12 review (UPLOAD_BASE_PATH of decision A, P3-6)
  Case 'UPLOAD_BASE_PATH missing' $Initial 'operational settings missing \(deploy-runbook 1.0\): UPLOAD_BASE_PATH' {
    $td = New-Initial; Remove-Env (App $td) 'UPLOAD_BASE_PATH'; $td }
  Case 'UPLOAD_BASE_PATH missing in the template' @() 'operational settings missing \(deploy-runbook 1.0\): UPLOAD_BASE_PATH' {
    $td = Read-Template; Remove-Env (App $td) 'UPLOAD_BASE_PATH'; $td }
  Case 'UPLOAD_BASE_PATH is the mount root' $SteadyOn 'UPLOAD_BASE_PATH must be exactly /app/uploads/current' {
    $td = New-Steady 'true'; Set-Env (App $td) 'UPLOAD_BASE_PATH' '/app/uploads'; $td }
  Case 'UPLOAD_BASE_PATH in another case' $Initial 'UPLOAD_BASE_PATH must be exactly /app/uploads/current' {
    $td = New-Initial; Set-Env (App $td) 'UPLOAD_BASE_PATH' '/app/uploads/Current'; $td }
  Case 'UPLOAD_BASE_PATH with a trailing slash' $Initial 'UPLOAD_BASE_PATH must be exactly /app/uploads/current' {
    $td = New-Initial; Set-Env (App $td) 'UPLOAD_BASE_PATH' '/app/uploads/current/'; $td }
  Case 'UPLOAD_BASE_PATH with a trailing space' $Initial 'UPLOAD_BASE_PATH must be exactly /app/uploads/current' {
    $td = New-Initial; Set-Env (App $td) 'UPLOAD_BASE_PATH' '/app/uploads/current '; $td }
  Case 'UPLOAD_BASE_PATH in the template with another value' @() 'UPLOAD_BASE_PATH must be exactly /app/uploads/current' {
    $td = Read-Template; Set-Env (App $td) 'UPLOAD_BASE_PATH' './uploads'; $td }
  Case 'upload_base_path in lower case' $Initial 'UPLOAD_BASE_PATH must be exactly /app/uploads/current' {
    $td = New-Initial; Remove-Env (App $td) 'UPLOAD_BASE_PATH'; Set-Env (App $td) 'upload_base_path' '/app/uploads/current'; $td }
  Case 'UPLOAD_BASE_PATH from the secret store' $Initial 'settings checked by value must be in environment' {
    $td = New-Initial; Remove-Env (App $td) 'UPLOAD_BASE_PATH'; Add-Secret (App $td) 'UPLOAD_BASE_PATH'; $td }
  Case 'APP_UPLOAD_BASE_PATH overrides the upload root' $Initial 'guarded property trees must use the short names' {
    $td = New-Initial; Set-Env (App $td) 'APP_UPLOAD_BASE_PATH' '/app/uploads'; $td }
  Case 'app.upload.base-path in the environment SPRING_APPLICATION_JSON' $Initial 'SPRING_APPLICATION_JSON sets guarded properties' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_APPLICATION_JSON' '{"app":{"upload":{"base-path":"/app/uploads"}}}'; $td }
  Case 'IMPORT_ROW_REFERENCE_HMAC_KEY in environment' $Initial 'names that look like secrets must come from the secret store' {
    $td = New-Initial; Set-Env (App $td) 'IMPORT_ROW_REFERENCE_HMAC_KEY' 'plain-text'; $td }
  Case 'CERTIFICATION_NUMBER_CRYPTO_KEYS_V2 in environment' $SteadyOn 'names that look like secrets must come from the secret store' {
    $td = New-Steady 'true'; Set-Env (App $td) 'CERTIFICATION_NUMBER_CRYPTO_KEYS_V2' 'plain-text'; $td }
  Case 'SMTP_PASSWORD in environment' $Initial 'names that look like secrets must come from the secret store' {
    $td = New-Initial; Set-Env (App $td) 'SMTP_PASSWORD' 'plain-text'; $td }

  # ---- backup write quiesce (RB-QUIESCE BWQ-007; deploy-runbook 1.0 rows 31-41, backup-write-quiesce design 12.3)
  Case 'actual, backup quiesce emergency stop with a change ticket' $SteadyOn 'PASS' {
    $td = New-Steady 'true'; Set-Env (App $td) 'BACKUP_QUIESCE_ENABLED' 'false'; Set-Env (App $td) 'BACKUP_QUIESCE_DISABLED_TICKET' 'CHG-1234'; $td }
  Case 'actual, backup quiesce values within range' $Initial 'PASS' {
    $td = New-Initial; $app = App $td
    Set-Env $app 'BACKUP_QUIESCE_MAX_HOLD_CHECKPOINT' '900s'; Set-Env $app 'BACKUP_QUIESCE_MAX_HOLD_FULL' '4h'
    Set-Env $app 'BACKUP_QUIESCE_POLL_INTERVAL' '500ms'; Set-Env $app 'BACKUP_QUIESCE_RETENTION' '365d'
    Set-Env $app 'BACKUP_QUIESCE_STARTUP_WAIT_MAX' '60s'; $td }
  Case 'actual, longer startup wait with a longer startPeriod' $Initial 'PASS' {
    $td = New-Initial; $app = App $td; Set-Env $app 'BACKUP_QUIESCE_STARTUP_WAIT_MAX' '2m'; $app.healthCheck.startPeriod = 240; $td }
  Case 'BACKUP_QUIESCE_ENABLED missing in the template' @() 'BACKUP_QUIESCE_ENABLED must be given once' {
    $td = Read-Template; Remove-Env (App $td) 'BACKUP_QUIESCE_ENABLED'; $td }
  Case 'BACKUP_QUIESCE_ENABLED missing' $Initial 'BACKUP_QUIESCE_ENABLED must be given once' {
    $td = New-Initial; Remove-Env (App $td) 'BACKUP_QUIESCE_ENABLED'; $td }
  Case 'BACKUP_QUIESCE_ENABLED=false in the template even with a ticket' @() 'the template must state BACKUP_QUIESCE_ENABLED=true' {
    $td = Read-Template; Set-Env (App $td) 'BACKUP_QUIESCE_ENABLED' 'false'; Set-Env (App $td) 'BACKUP_QUIESCE_DISABLED_TICKET' 'CHG-1234'; $td }
  Case 'BACKUP_QUIESCE_ENABLED=TRUE (other spelling of the value)' $Initial 'BACKUP_QUIESCE_ENABLED must be true or false' {
    $td = New-Initial; Set-Env (App $td) 'BACKUP_QUIESCE_ENABLED' 'TRUE'; $td }
  Case 'emergency stop without a ticket' $SteadyOn 'needs BACKUP_QUIESCE_DISABLED_TICKET in the change-ticket form' {
    $td = New-Steady 'true'; Set-Env (App $td) 'BACKUP_QUIESCE_ENABLED' 'false'; $td }
  Case 'emergency stop with an empty ticket' $SteadyOn 'needs BACKUP_QUIESCE_DISABLED_TICKET in the change-ticket form' {
    $td = New-Steady 'true'; Set-Env (App $td) 'BACKUP_QUIESCE_ENABLED' 'false'; Set-Env (App $td) 'BACKUP_QUIESCE_DISABLED_TICKET' ''; $td }
  Case 'emergency stop with a malformed ticket' $SteadyOn 'needs BACKUP_QUIESCE_DISABLED_TICKET in the change-ticket form' {
    $td = New-Steady 'true'; Set-Env (App $td) 'BACKUP_QUIESCE_ENABLED' 'false'; Set-Env (App $td) 'BACKUP_QUIESCE_DISABLED_TICKET' 'chg-1234'; $td }
  Case 'ticket left with BACKUP_QUIESCE_ENABLED=true' $SteadyOn 'belongs only to BACKUP_QUIESCE_ENABLED=false' {
    $td = New-Steady 'true'; Set-Env (App $td) 'BACKUP_QUIESCE_DISABLED_TICKET' 'CHG-1234'; $td }
  Case 'BACKUP_QUIESCE_ENABLED from the secret store' $Initial 'settings checked by value must be in environment' {
    $td = New-Initial; Remove-Env (App $td) 'BACKUP_QUIESCE_ENABLED'; Add-Secret (App $td) 'BACKUP_QUIESCE_ENABLED'; $td }
  Case 'BACKUP_QUIESCE_DISABLED_TICKET from the secret store' $SteadyOn 'settings checked by value must be in environment' {
    $td = New-Steady 'true'; Set-Env (App $td) 'BACKUP_QUIESCE_ENABLED' 'false'; Add-Secret (App $td) 'BACKUP_QUIESCE_DISABLED_TICKET'; $td }
  Case 'APP_BACKUP_QUIESCE_ENABLED (canonical name)' $Initial 'guarded property trees must use the short names' {
    $td = New-Initial; Set-Env (App $td) 'APP_BACKUP_QUIESCE_ENABLED' 'false'; $td }
  Case 'APP_BACKUPQUIESCE_DISABLEDTICKET (canonical name)' $SteadyOn 'guarded property trees must use the short names' {
    $td = New-Steady 'true'; Set-Env (App $td) 'APP_BACKUPQUIESCE_DISABLEDTICKET' 'CHG-1234'; $td }
  Case 'app.backup-quiesce.enabled in the environment SPRING_APPLICATION_JSON' $Initial 'SPRING_APPLICATION_JSON sets guarded properties' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_APPLICATION_JSON' '{"app":{"backup-quiesce":{"enabled":false}}}'; $td }
  Case 'backup_quiesce_enabled in lower case' $Initial 'backup quiesce settings must use the documented names' {
    $td = New-Initial; Remove-Env (App $td) 'BACKUP_QUIESCE_ENABLED'; Set-Env (App $td) 'backup_quiesce_enabled' 'true'; $td }
  Case 'BACKUP_QUIESCE_MAX_HOLD_CHECKPOINT out of range' $Initial 'BACKUP_QUIESCE_MAX_HOLD_CHECKPOINT must be 30s to 900s' {
    $td = New-Initial; Set-Env (App $td) 'BACKUP_QUIESCE_MAX_HOLD_CHECKPOINT' '901s'; $td }
  Case 'BACKUP_QUIESCE_DRAIN_TIMEOUT_FULL not shorter than the full hold' $Initial 'BACKUP_QUIESCE_MAX_HOLD_FULL must be 60s to 4h' {
    $td = New-Initial; Set-Env (App $td) 'BACKUP_QUIESCE_DRAIN_TIMEOUT_FULL' '3600s'; $td }
  Case 'BACKUP_QUIESCE_FENCE_AFTER too close to the heartbeat' $Initial 'must be longer than 13 seconds' {
    $td = New-Initial; Set-Env (App $td) 'BACKUP_QUIESCE_FENCE_AFTER' '18s'; $td }
  Case 'BACKUP_QUIESCE_POLL_INTERVAL below 200ms' $Initial 'BACKUP_QUIESCE_POLL_INTERVAL must be at least 200ms' {
    $td = New-Initial; Set-Env (App $td) 'BACKUP_QUIESCE_POLL_INTERVAL' '100ms'; $td }
  Case 'BACKUP_QUIESCE_RETENTION out of range' $Initial 'BACKUP_QUIESCE_RETENTION must be 1d to 365d' {
    $td = New-Initial; Set-Env (App $td) 'BACKUP_QUIESCE_RETENTION' '400d'; $td }
  Case 'BACKUP_QUIESCE_STARTUP_WAIT_MAX out of range' $Initial 'BACKUP_QUIESCE_STARTUP_WAIT_MAX must be 10s to 120s' {
    $td = New-Initial; Set-Env (App $td) 'BACKUP_QUIESCE_STARTUP_WAIT_MAX' '5s'; $td }
  Case 'BACKUP_QUIESCE_MAX_HOLD_FULL without a unit' $Initial 'must be a whole number with a unit' {
    $td = New-Initial; Set-Env (App $td) 'BACKUP_QUIESCE_MAX_HOLD_FULL' '3600'; $td }
  Case 'startPeriod shorter than the startup wait + 120 s' $Initial 'startPeriod must be at least BACKUP_QUIESCE_STARTUP_WAIT_MAX \+ 120' {
    $td = New-Initial; (App $td).healthCheck.startPeriod = 120; $td }
  Case 'startPeriod too short for a longer startup wait' $Initial 'startPeriod must be at least BACKUP_QUIESCE_STARTUP_WAIT_MAX \+ 120' {
    $td = New-Initial; Set-Env (App $td) 'BACKUP_QUIESCE_STARTUP_WAIT_MAX' '120s'; $td }
  Case 'stopTimeout missing in the template' @() 'stopTimeout must be at least SHUTDOWN_TIMEOUT \+ 60' {
    $td = Read-Template; $null = (App $td).PSObject.Properties.Remove('stopTimeout'); $td }
  Case 'stopTimeout too short' $Initial 'stopTimeout must be at least SHUTDOWN_TIMEOUT \+ 60' {
    $td = New-Initial; (App $td).stopTimeout = 60; $td }
  Case 'stopTimeout too short for a longer SHUTDOWN_TIMEOUT' $Initial 'stopTimeout must be at least SHUTDOWN_TIMEOUT \+ 60' {
    $td = New-Initial; Set-Env (App $td) 'SHUTDOWN_TIMEOUT' '61s'; $td }
  Case 'SHUTDOWN_TIMEOUT from the secret store' $Initial 'settings checked by value must be in environment' {
    $td = New-Initial; Remove-Env (App $td) 'SHUTDOWN_TIMEOUT'; Add-Secret (App $td) 'SHUTDOWN_TIMEOUT'; $td }
  Case 'SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE replaces SHUTDOWN_TIMEOUT' $Initial 'guarded property trees must use the short names' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE' '100s'; $td }

  # ---- feature enablement (FE-A08, feature-enablement-console design section 10, deploy-runbook 1.0 row 42)
  Case 'feature enablement master switch false (emergency stop, first start after a restore)' $SteadyOn 'PASS' {
    $td = New-Steady 'true'; Set-Env (App $td) 'FEATURE_ENABLEMENT_ALLOWED' 'false'; $td }
  Case 'FEATURE_ENABLEMENT_ALLOWED from the secret store' $Initial 'settings checked by value must be in environment' {
    $td = New-Initial; Remove-Env (App $td) 'FEATURE_ENABLEMENT_ALLOWED'; Add-Secret (App $td) 'FEATURE_ENABLEMENT_ALLOWED'; $td }
  Case 'FEATURE_ENABLEMENT_ALLOWED missing' $Initial 'FEATURE_ENABLEMENT_ALLOWED missing' {
    $td = New-Initial; Remove-Env (App $td) 'FEATURE_ENABLEMENT_ALLOWED'; $td }
  Case 'FEATURE_ENABLEMENT_ALLOWED=yes' $Initial 'FEATURE_ENABLEMENT_ALLOWED must be exactly true or false' {
    $td = New-Initial; Set-Env (App $td) 'FEATURE_ENABLEMENT_ALLOWED' 'yes'; $td }
  Case 'FEATURE_ENABLEMENT_ALLOWED=TRUE (upper case)' $SteadyOn 'FEATURE_ENABLEMENT_ALLOWED must be exactly true or false' {
    $td = New-Steady 'true'; Set-Env (App $td) 'FEATURE_ENABLEMENT_ALLOWED' 'TRUE'; $td }
  Case 'APP_FEATURE_ENABLEMENT_MAX_STALENESS override' $SteadyOn 'feature-enablement operational settings must not be set' {
    $td = New-Steady 'true'; Set-Env (App $td) 'APP_FEATURE_ENABLEMENT_MAX_STALENESS' 'PT300S'; $td }
  Case 'app.feature-enablement.source override' $Initial 'feature-enablement operational settings must not be set' {
    $td = New-Initial; Set-Env (App $td) 'app.feature-enablement.source' 'properties'; $td }
  Case 'FEATURE_ENABLEMENT_EVALUATION_INTERVAL override' $SteadyOn 'feature-enablement operational settings must not be set' {
    $td = New-Steady 'true'; Set-Env (App $td) 'FEATURE_ENABLEMENT_EVALUATION_INTERVAL' 'PT60M'; $td }
  Case 'APP_FEATURE_ENABLEMENT_ALLOWED (canonical name of the switch)' $Initial 'feature-enablement operational settings must not be set' {
    $td = New-Initial; Set-Env (App $td) 'APP_FEATURE_ENABLEMENT_ALLOWED' 'true'; $td }
  Case 'app.feature-enablement in the environment SPRING_APPLICATION_JSON' $Initial 'SPRING_APPLICATION_JSON sets guarded properties' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_APPLICATION_JSON' '{"app":{"feature-enablement":{"max-staleness":"PT300S"}}}'; $td }
  Case 'FEATURE_ENABLEMENT_ALLOWED in the environment SPRING_APPLICATION_JSON' $Initial 'SPRING_APPLICATION_JSON sets guarded properties' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_APPLICATION_JSON' '{"FEATURE_ENABLEMENT_ALLOWED":"true"}'; $td }
  # ---- transitional refusals until FE-B01 and FE-B03 (implementation review rb-fe-a2 P2-1, P2-2)
  Case 'steady, NF01 dual confirmation left false' $SteadyOn 'PASS' {
    $td = New-Steady 'true'; Set-Env (App $td) 'FEATURE_NF01_DUAL_CONFIRMATION_ENABLED' 'false'; $td }
  Case 'steady, NF02 customer portal visible-since left empty' $SteadyOn 'PASS' {
    $td = New-Steady 'true'; Set-Env (App $td) 'FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE' ''; $td }
  Case 'FEATURE_NF01_DUAL_CONFIRMATION_ENABLED=true before FE-B01' $SteadyOn 'FEATURE_NF01_DUAL_CONFIRMATION_ENABLED must not be true until FE-B01' {
    $td = New-Steady 'true'; Set-Env (App $td) 'FEATURE_NF01_DUAL_CONFIRMATION_ENABLED' 'true'; $td }
  Case 'FEATURE_NF01_DUAL_CONFIRMATION_ENABLED=ON before FE-B01' $SteadyOn 'FEATURE_NF01_DUAL_CONFIRMATION_ENABLED must not be true until FE-B01' {
    $td = New-Steady 'true'; Set-Env (App $td) 'FEATURE_NF01_DUAL_CONFIRMATION_ENABLED' 'ON'; $td }
  Case 'FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE before FE-B03' $SteadyOn 'FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE must not be set until FE-B03' {
    $td = New-Steady 'true'; Set-Env (App $td) 'FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE' '2027-04-01T00:00:00+09:00'; $td }
  Case 'FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE from the secret store before FE-B03' $SteadyOn 'FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE must not be set until FE-B03' {
    $td = New-Steady 'true'; Add-Secret (App $td) 'FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE'; $td }

  # ---- negative cases kept from earlier rounds
  Case 'profile prod,dev' $Initial 'SPRING_PROFILES_ACTIVE must be exactly prod' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_PROFILES_ACTIVE' 'prod,dev'; $td }
  Case 'SPRING_CONFIG_IMPORT' $Initial 'extra profile or configuration sources' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_CONFIG_IMPORT' 'optional:file:/tmp/x.yml'; $td }
  Case 'app command override' $Initial 'ses-manager must keep the image command' {
    $td = New-Initial; Set-Prop (App $td) 'command' @('--app.scheduling.enabled=true'); $td }
  Case 'environmentFiles' $Initial 'must not use environmentFiles' {
    $td = New-Initial; Set-Prop (App $td) 'environmentFiles' @([pscustomobject]@{ value = 'arn:aws:s3:::bucket/app.env'; type = 's3' }); $td }
  Case 'quoted -D in JAVA_TOOL_OPTIONS' $Initial 'system properties other than user.timezone' {
    $td = New-Initial; Set-Env (App $td) 'JAVA_TOOL_OPTIONS' '-Duser.timezone=Asia/Tokyo "-Dapp.scheduling.enabled=true"'; $td }
  Case '@argfile in JDK_JAVA_OPTIONS' $Initial 'must not read an @argfile' {
    $td = New-Initial; Set-Env (App $td) 'JDK_JAVA_OPTIONS' '@/tmp/jvm.args'; $td }
  Case 'management in the environment SPRING_APPLICATION_JSON' $Initial 'SPRING_APPLICATION_JSON sets guarded properties' {
    $td = New-Initial; Set-Env (App $td) 'SPRING_APPLICATION_JSON' '{"management":{"server":{"port":9090}}}'; $td }
  Case 'APP_SCHEDULING_ENABLED from the secret store' $Initial 'settings checked by value must be in environment' {
    $td = New-Initial; Remove-Env (App $td) 'APP_SCHEDULING_ENABLED'; Add-Secret (App $td) 'APP_SCHEDULING_ENABLED'; $td }
  Case 'clamav writable root filesystem' $Initial 'clamav must run with a read-only root filesystem' {
    $td = New-Initial; (Scanner $td).readonlyRootFilesystem = $false; $td }
  Case 'clamav with a secret' $Initial 'clamav must not receive secrets' {
    $td = New-Initial; Add-Secret (Scanner $td) 'CLAMAV_TOKEN'; $td }
  Case 'clamav mounts uploads' $Initial 'clamav may mount only its own' {
    $td = New-Initial; $scan = Scanner $td
    Set-Prop $scan 'mountPoints' (@($scan.mountPoints) + [pscustomobject]@{ sourceVolume = 'uploads'; containerPath = '/scan'; readOnly = $true }); $td }
  Case 'clamav publishes a port' $Initial 'clamav must not publish a port' {
    $td = New-Initial; Set-Prop (Scanner $td) 'portMappings' @([pscustomobject]@{ containerPort = 3310; protocol = 'tcp' }); $td }
  Case 'steady enabled with the scheduler false' $SteadyOn 'must be true or unset' { New-Steady 'false' }
  Case 'steady disabled with the scheduler unset' $SteadyOff 'APP_SCHEDULING_ENABLED=false expected' {
    $td = New-Steady 'false'; Remove-Env (App $td) 'APP_SCHEDULING_ENABLED'; $td }
  Case 'bootstrap expiry more than 24 hours ahead' $Initial 'more than 24 hours ahead' {
    $td = New-Initial
    Set-Env (App $td) 'SECURITY_BOOTSTRAP_EXPIRES_AT' ([DateTimeOffset]::Now.AddHours(30).ToString("yyyy-MM-dd'T'HH:mm:sszzz", [System.Globalization.CultureInfo]::InvariantCulture)); $td }
  Case 'same setting in environment and secrets (other spelling)' $Initial 'settings given more than once' {
    $td = New-Initial; Add-Secret (App $td) 'trusted_proxies'; $td }
  Case 'placeholder left' $Initial 'placeholders left' {
    $td = New-Initial; Set-Env (App $td) 'OIDC_ISSUER_URI' 'OIDC_ISSUER_URI_PLACEHOLDER'; $td }
  Case 'image by tag' $Initial 'must be pinned by a registry digest' {
    $td = New-Initial; (App $td).image = '123456789012.dkr.ecr.ap-northeast-1.amazonaws.com/ses-manager-pro:latest'; $td }
  Case 'privacy-trust on the uploads access point' $Initial 'privacy-trust must use its own EFS access point' {
    $td = New-Initial; (Vol $td 'privacy-trust').efsVolumeConfiguration.authorizationConfig.accessPointId = 'fsap-0123456789abcdef0'; $td }
  Case 'template with an uploads rootDirectory (the inline EFS rule)' @() 'invalid EFS rootDirectory with accessPointId' {
    $td = Read-Template; Set-Prop (Vol $td 'uploads').efsVolumeConfiguration 'rootDirectory' '/ses-manager/uploads'; $td }
} finally {
  Remove-Item $Work -Recurse -Force -ErrorAction SilentlyContinue
}

$Results | ForEach-Object { Write-Host $_ }
$total = $Results.Count
Write-Host ("validator self-test on PowerShell {0} ({1}): {2} cases, {3} mismatched" -f $PSVersionTable.PSVersion, $PSVersionTable.PSEdition, $total, $script:Mismatch)
if ($total -eq 0) { Write-Host 'no case matched -Filter'; exit 2 }
if ($script:Mismatch -gt 0) { exit 1 }
exit 0
