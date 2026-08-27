#Requires -Version 5.1
# Static validation of task-definition.prod.json — no AWS API calls.
$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$TdPath = Join-Path $PSScriptRoot '..\task-definition.prod.json'
$gitSha = (git -C $RepoRoot rev-parse HEAD).Trim()
$Ev = Join-Path $RepoRoot ".kiro\reviews\production-acceptance\$gitSha\offline-ecs-simulation"
New-Item -ItemType Directory -Force -Path $Ev | Out-Null

$td = Get-Content $TdPath -Raw | ConvertFrom-Json
$lines = New-Object System.Collections.Generic.List[string]
function Ok([string]$m) { $script:lines.Add("PASS $m") }
function Fail([string]$m) { $script:lines.Add("FAIL $m"); throw $m }

if ($td.networkMode -ne 'awsvpc') { Fail 'networkMode!=awsvpc' } else { Ok 'networkMode=awsvpc' }
if ($td.requiresCompatibilities -notcontains 'FARGATE') { Fail 'missing FARGATE' } else { Ok 'requiresCompatibilities contains FARGATE' }

$validCombos = @{
  '256' = @('512','1024','2048')
  '512' = @('1024','2048','3072','4096')
  '1024' = @('2048','3072','4096','5120','6144','7168','8192')
  '2048' = @('4096','5120','6144','7168','8192','9216','10240','11264','12288','13312','14336','15360','16384')
  '4096' = @('8192','9216','10240','11264','12288','13312','14336','15360','16384','17408','18432','19456','20480','21504','22528','23552','24576','25600','26624','27648','28672','29696','30720')
}
$cpu = [string]$td.cpu
$mem = [string]$td.memory
if (-not $validCombos.ContainsKey($cpu) -or $validCombos[$cpu] -notcontains $mem) {
  Fail "illegal Fargate cpu/memory combo cpu=$cpu mem=$mem"
} else { Ok "cpu/memory combo legal ($cpu/$mem)" }

$c = $td.containerDefinitions[0]
if (-not $c.essential) { Fail 'essential!=true' } else { Ok 'essential=true' }
if (-not $c.readonlyRootFilesystem) { Fail 'readonlyRootFilesystem missing/false' } else { Ok 'readonlyRootFilesystem=true' }
if (-not $c.linuxParameters -or -not $c.linuxParameters.initProcessEnabled) {
  Fail 'linuxParameters.initProcessEnabled missing'
} else { Ok 'linuxParameters.initProcessEnabled=true' }

$port = $c.portMappings[0]
if ($port.containerPort -ne 8080) { Fail 'portMappings' } else { Ok 'portMappings 8080/tcp' }

if ($c.image -notmatch '@sha256:IMAGE_DIGEST$') {
  Fail 'image must keep ECR@sha256:IMAGE_DIGEST placeholder'
} else { Ok 'image uses ECR URI@IMAGE_DIGEST placeholder (not LOCAL_IMAGE_ID)' }

$envNames = @($c.environment | ForEach-Object { $_.name })
foreach ($forbidden in @('DB_PASSWORD','DB_USERNAME','DB_URL','OIDC_CLIENT_SECRET','BATCH_TOKEN_SECRET')) {
  if ($envNames -contains $forbidden) { Fail "$forbidden must not be plaintext environment" }
}
Ok 'no DB/OIDC/batch secrets in environment[]'

$secretNames = @($c.secrets | ForEach-Object { $_.name })
foreach ($need in @('DB_PASSWORD','DB_USERNAME','DB_URL')) {
  if ($secretNames -notcontains $need) { Fail "secrets missing $need" }
}
Ok 'DB_* via secrets valueFrom placeholders'

$hc = ($c.healthCheck.command -join ' ')
if ($hc -notmatch 'actuator/health/liveness') { Fail 'healthCheck path' } else { Ok 'healthCheck matches /actuator/health/liveness' }

if ($c.logConfiguration.logDriver -ne 'awslogs') { Fail 'logDriver' } else {
  Ok 'awslogs present as PLACEHOLDER — CloudWatch NOT verified offline (DEFERRED)'
}
if ($td.executionRoleArn -notmatch 'ACCOUNT_ID') { Fail 'executionRoleArn placeholder' } else {
  Ok 'executionRoleArn ACCOUNT_ID placeholder (IAM OUT-OF-SCOPE)'
}
Ok 'deferred: ALB, SG, private subnet, circuit breaker service, autoscaling, ECR push'

$utf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllLines((Join-Path $Ev 'task-definition-validation.txt'), $lines.ToArray(), $utf8)
Write-Host 'task-definition static validation PASS'
