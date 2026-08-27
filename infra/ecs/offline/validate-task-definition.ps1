#Requires -Version 5.1
# Static validation of task-definition.prod.json — no AWS API calls.
param(
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
$gitSha = (git -C $RepoRoot rev-parse HEAD).Trim()
if (-not $EvidenceRoot) {
  $EvidenceRoot = Join-Path $RepoRoot ".kiro\reviews\production-acceptance\$gitSha\offline-ecs-simulation"
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

if (-not $SkipInlineNegativeSelfTest) {
  $null = Invoke-NegativeEfsSelfTest -OutEvidenceRoot $EvidenceRoot
}

Write-Host 'task-definition static validation PASS'
