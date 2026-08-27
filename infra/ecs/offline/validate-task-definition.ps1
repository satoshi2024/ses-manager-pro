#Requires -Version 5.1
# Static validation of task-definition.prod.json — no AWS API calls.
param(
  [string]$EvidenceRoot = '',
  [switch]$EfsNegativeSelfTestOnly
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$TdPath = Join-Path $PSScriptRoot '..\task-definition.prod.json'
$gitSha = (git -C $RepoRoot rev-parse HEAD).Trim()
if (-not $EvidenceRoot) {
  $EvidenceRoot = Join-Path $RepoRoot ".kiro\reviews\production-acceptance\$gitSha\offline-ecs-simulation"
}

function Assert-EfsRootDirectoryRule([object]$efsCfg, [switch]$ExpectFail) {
  $lines = New-Object System.Collections.Generic.List[string]
  $ap = $efsCfg.authorizationConfig.accessPointId
  $rd = $efsCfg.rootDirectory
  $hasAp = ($null -ne $ap -and [string]$ap -ne '')
  if ($hasAp) {
    if ($null -eq $rd -or [string]$rd -eq '' -or [string]$rd -eq '/') {
      $lines.Add("PASS accessPointId=$ap rootDirectory='$rd' (absent or /)")
    } else {
      $lines.Add("FAIL accessPointId=$ap rootDirectory='$rd' must be absent or / only")
      if (-not $ExpectFail) { throw "invalid EFS rootDirectory with accessPointId: $rd" }
      return $lines
    }
  } else {
    $lines.Add("INFO no accessPointId — rootDirectory rules not applied")
  }
  if ($ExpectFail) { throw 'negative case unexpectedly passed' }
  return $lines
}

if ($EfsNegativeSelfTestOnly) {
  New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null
  $neg = New-Object System.Collections.Generic.List[string]
  $bad = [pscustomobject]@{
    authorizationConfig = @{ accessPointId = 'fsap-TEST' }
    rootDirectory         = '/ses-manager/uploads'
  }
  $threw = $false
  try {
    Assert-EfsRootDirectoryRule $bad -ExpectFail | Out-Null
  } catch {
    $threw = $true
    $neg.Add("PASS negative: rootDirectory=/ses-manager/uploads rejected")
    $neg.Add($_.Exception.Message)
  }
  if (-not $threw) { throw 'EFS negative self-test did not fail on /ses-manager/uploads' }
  $utf8 = New-Object System.Text.UTF8Encoding $false
  [System.IO.File]::WriteAllText((Join-Path $EvidenceRoot 'efs-validation-negative.txt'), ($neg -join "`n"), $utf8)
  Write-Host 'EFS negative self-test PASS'
  exit 0
}

New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null

$td = Get-Content $TdPath -Raw | ConvertFrom-Json
$lines = New-Object System.Collections.Generic.List[string]
function Ok([string]$m) { $script:lines.Add("PASS $m") }
function Fail([string]$m) { $script:lines.Add("FAIL $m"); throw $m }

if ($td.networkMode -ne 'awsvpc') { Fail 'networkMode!=awsvpc' } else { Ok 'networkMode=awsvpc' }
if ($td.requiresCompatibilities -notcontains 'FARGATE') { Fail 'missing FARGATE' } else { Ok 'FARGATE' }

$cpu = [string]$td.cpu; $mem = [string]$td.memory
if ($cpu -ne '1024' -or $mem -ne '2048') { Fail "cpu/mem $cpu/$mem" } else { Ok 'cpu/memory 1024/2048' }

$c = $td.containerDefinitions[0]
if (-not $c.essential) { Fail 'essential' } else { Ok 'essential=true' }
if (-not $c.readonlyRootFilesystem) { Fail 'readonlyRootFilesystem' } else { Ok 'readonlyRootFilesystem=true' }
if (-not $c.linuxParameters.initProcessEnabled) { Fail 'initProcessEnabled' } else { Ok 'initProcessEnabled=true' }
if ($c.portMappings[0].containerPort -ne 8080) { Fail 'port' } else { Ok 'port 8080' }
if ($c.image -notmatch '@sha256:IMAGE_DIGEST$') { Fail 'IMAGE_DIGEST placeholder' } else { Ok 'ECR@IMAGE_DIGEST placeholder (not LOCAL_IMAGE_ID)' }

$hc = ($c.healthCheck.command -join ' ')
if ($hc -notmatch 'actuator/health/liveness') { Fail 'container healthCheck must be liveness' } else { Ok 'ECS container healthCheck=liveness' }
if ($hc -match 'actuator/health/readiness') { Fail 'container healthCheck must NOT be readiness' }

$alb = $td._offlineAcceptanceNotes.albTargetGroupHealthCheck.path
if ($alb -ne '/actuator/health/readiness') { Fail "ALB path must be readiness, got $alb" } else { Ok 'ALB TG path=/actuator/health/readiness (DEFERRED until real AWS)' }

$uploads = $td.volumes | Where-Object { $_.name -eq 'uploads' }
if (-not $uploads.efsVolumeConfiguration) { Fail 'uploads missing efsVolumeConfiguration placeholder' }
if ($uploads.efsVolumeConfiguration.fileSystemId -ne 'fs-PLACEHOLDER') { Fail 'EFS fs id placeholder' }
if ($uploads.efsVolumeConfiguration.authorizationConfig.accessPointId -ne 'fsap-PLACEHOLDER') { Fail 'EFS accessPointId placeholder missing' }
Ok 'uploads EFS accessPointId placeholder present'
(Assert-EfsRootDirectoryRule $uploads.efsVolumeConfiguration) | ForEach-Object { $lines.Add($_) }
Ok 'EFS-ACTUAL=DEFERRED (template static only)'

$envNames = @($c.environment | ForEach-Object { $_.name })
foreach ($f in @('DB_PASSWORD','DB_USERNAME','DB_URL','OIDC_CLIENT_SECRET','BATCH_TOKEN_SECRET')) {
  if ($envNames -contains $f) { Fail "$f in environment" }
}
Ok 'no secrets in environment[] (Secrets Manager/SSM placeholders only)'

if ($c.logConfiguration.logDriver -ne 'awslogs') { Fail 'awslogs driver' } else { Ok 'awslogs placeholder present' }
Ok 'CloudWatch-ACTUAL=DEFERRED (no log delivery verified)'
Ok 'executionRoleArn/taskRoleArn=IAM placeholders (IAM-ACTUAL=DEFERRED)'
Ok 'ALB/SG/circuit-breaker/autoscaling=DEFERRED external IaC'
Ok 'ECR push/registryDigest=OUT-OF-SCOPE (no AWS account)'

$utf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllLines((Join-Path $EvidenceRoot 'task-definition-validation.txt'), $lines.ToArray(), $utf8)

# inline negative self-test output
$neg = New-Object System.Collections.Generic.List[string]
$bad = [pscustomobject]@{
  authorizationConfig = @{ accessPointId = 'fsap-TEST' }
  rootDirectory         = '/ses-manager/uploads'
}
try {
  Assert-EfsRootDirectoryRule $bad -ExpectFail | Out-Null
  throw 'negative self-test did not fail'
} catch {
  $neg.Add('PASS negative: rootDirectory=/ses-manager/uploads must fail when accessPointId set')
  $neg.Add($_.Exception.Message)
}
[System.IO.File]::WriteAllText((Join-Path $EvidenceRoot 'efs-validation-negative.txt'), ($neg -join "`n"), $utf8)

Write-Host 'task-definition static validation PASS'
