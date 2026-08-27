#Requires -Version 5.1
# Static validation of task-definition.prod.json — no AWS API calls.
$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$TdPath = Join-Path $PSScriptRoot '..\task-definition.prod.json'
$gitSha = (git -C $RepoRoot rev-parse HEAD).Trim()
$Ev = Join-Path $RepoRoot ".kiro\reviews\production-acceptance\$gitSha\offline-ecs-simulation-2.1"
New-Item -ItemType Directory -Force -Path $Ev | Out-Null

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
if ($c.image -notmatch '@sha256:IMAGE_DIGEST$') { Fail 'IMAGE_DIGEST placeholder' } else { Ok 'ECR@IMAGE_DIGEST placeholder' }

$hc = ($c.healthCheck.command -join ' ')
if ($hc -notmatch 'actuator/health/liveness') { Fail 'container healthCheck must be liveness' } else { Ok 'ECS container healthCheck=liveness' }
if ($hc -match 'actuator/health/readiness') { Fail 'container healthCheck must NOT be readiness' }

$alb = $td._offlineAcceptanceNotes.albTargetGroupHealthCheck.path
if ($alb -ne '/actuator/health/readiness') { Fail "ALB path must be readiness, got $alb" } else { Ok 'ALB TG path=/actuator/health/readiness (documented)' }

$uploads = $td.volumes | Where-Object { $_.name -eq 'uploads' }
if (-not $uploads.efsVolumeConfiguration) { Fail 'uploads missing efsVolumeConfiguration placeholder' }
if ($uploads.efsVolumeConfiguration.fileSystemId -ne 'fs-PLACEHOLDER') { Fail 'EFS fs id placeholder' }
Ok 'uploads EFS template PRESENT (EFS-ACTUAL=BLOCKED)'

$envNames = @($c.environment | ForEach-Object { $_.name })
foreach ($f in @('DB_PASSWORD','DB_USERNAME','DB_URL','OIDC_CLIENT_SECRET','BATCH_TOKEN_SECRET')) {
  if ($envNames -contains $f) { Fail "$f in environment" }
}
Ok 'no secrets in environment[]'

$utf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllLines((Join-Path $Ev 'task-definition-validation.txt'), $lines.ToArray(), $utf8)
Write-Host 'task-definition static validation PASS'
