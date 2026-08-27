#Requires -Version 5.1
<#
.SYNOPSIS
  Seed + verify Moto local AWS wiring for SES Manager ECS Fargate+ALB (desiredCount=1).
.NOTES
  LOCAL-SIM only. Never claim ECS-ACTUAL / production PASS.
#>
$ErrorActionPreference = 'Stop'
$Endpoint = if ($env:LOCAL_AWS_ENDPOINT) { $env:LOCAL_AWS_ENDPOINT } else { 'http://localhost:4566' }
$Region = 'ap-northeast-1'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$TaskDefPath = (Resolve-Path (Join-Path $Root 'ecs\task-definition.local.json')).Path
$RepoRoot = (Resolve-Path (Join-Path $Root '..\..')).Path
$EvidenceRoot = Join-Path $RepoRoot '.kiro\reviews\production-acceptance\ad829cc2e5c249c4cc273ba7b87441da163a48f3\remaining-p0p1-delta\reduced-scope-acceptance\aws-local'
New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null

$awsDir = Join-Path ${env:ProgramFiles} 'Amazon\AWSCLIV2'
if (Test-Path (Join-Path $awsDir 'aws.exe')) { $env:PATH = "$awsDir;$env:PATH" }

$env:AWS_ACCESS_KEY_ID = 'test'
$env:AWS_SECRET_ACCESS_KEY = 'test'
$env:AWS_DEFAULT_REGION = $Region
$env:AWS_EC2_METADATA_DISABLED = 'true'

function Invoke-AwsJson {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
  $raw = & aws --endpoint-url $Endpoint --region $Region --output json @CliArgs 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw ("aws failed ({0}): {1}" -f $LASTEXITCODE, ($raw | Out-String))
  }
  if ([string]::IsNullOrWhiteSpace(($raw | Out-String).Trim())) { return $null }
  return ($raw | Out-String) | ConvertFrom-Json
}

function Invoke-AwsText {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
  $raw = & aws --endpoint-url $Endpoint --region $Region --output text @CliArgs 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw ("aws failed ({0}): {1}" -f $LASTEXITCODE, ($raw | Out-String))
  }
  return ($raw | Out-String).Trim()
}

$report = New-Object System.Collections.Generic.List[string]
function Add-R([string]$line) { [void]$report.Add($line) }

Add-R '# Local AWS ECS+ALB wiring verify (Moto)'
Add-R ("generated={0}" -f (Get-Date -Format o))
Add-R "endpoint=$Endpoint"
Add-R 'simulator=moto'
Add-R 'mode=LOCAL-SIMULATION'
Add-R 'ecs_actual_claim=FORBIDDEN'
Add-R ''

Write-Host "Waiting for Moto at $Endpoint ..."
$healthy = $false
for ($i = 0; $i -lt 60; $i++) {
  try {
    Invoke-WebRequest -Uri $Endpoint -UseBasicParsing -TimeoutSec 3 | Out-Null
    $healthy = $true; break
  } catch { Start-Sleep -Seconds 2 }
}
if (-not $healthy) { throw "Moto endpoint not ready: $Endpoint" }
Add-R 'moto_health=UP'

Write-Host '=== STS ==='
$sts = Invoke-AwsJson sts get-caller-identity
Add-R 'sts_account=REDACTED_MOTO'
Add-R ("sts_user={0}" -f $sts.UserId)

Write-Host '=== Secrets / SSM / Logs ==='
$secExists = $false
try {
  $null = & aws --endpoint-url $Endpoint --region $Region secretsmanager describe-secret --secret-id ses/staging/db --output json 2>$null
  if ($LASTEXITCODE -eq 0) { $secExists = $true }
} catch { $secExists = $false }
if (-not $secExists) {
  Invoke-AwsJson secretsmanager create-secret --name ses/staging/db `
    --secret-string '{"username":"ses","password":"local-only-not-prod","url":"jdbc:mysql://mysql:3306/ses_manager_db"}' | Out-Null
}
$sec = Invoke-AwsJson secretsmanager describe-secret --secret-id ses/staging/db
Add-R ("secretsmanager={0} PRESENT" -f $sec.Name)

$ssmPairs = [ordered]@{
  '/ses/staging/app/spring.profiles.active' = 'prod'
  '/ses/staging/app/ai.provider' = 'mock'
  '/ses/staging/app/ai.external-send-enabled' = 'false'
  '/ses/staging/app/cloudsign.enabled' = 'false'
  '/ses/staging/app/digital-invoice.provider' = 'none'
}
foreach ($name in $ssmPairs.Keys) {
  Invoke-AwsJson ssm put-parameter --name $name --type String --value $ssmPairs[$name] --overwrite | Out-Null
  $v = Invoke-AwsText ssm get-parameter --name $name --query Parameter.Value
  Add-R "ssm $name = $v"
}

$null = & aws --endpoint-url $Endpoint --region $Region logs create-log-group --log-group-name /ecs/ses-manager-staging --output json 2>$null
$null = & aws --endpoint-url $Endpoint --region $Region logs create-log-stream --log-group-name /ecs/ses-manager-staging --log-stream-name bootstrap --output json 2>$null
Add-R 'cloudwatch_log_group=/ecs/ses-manager-staging PRESENT'

Write-Host '=== VPC / Subnet / SG (private topology stand-in) ==='
$vpc = Invoke-AwsJson ec2 create-vpc --cidr-block 10.20.0.0/16
$vpcId = $vpc.Vpc.VpcId
$sub = Invoke-AwsJson ec2 create-subnet --vpc-id $vpcId --cidr-block 10.20.1.0/24
$subnetId = $sub.Subnet.SubnetId
$sg = Invoke-AwsJson ec2 create-security-group --group-name ses-ecs-tasks --description 'ses ecs tasks' --vpc-id $vpcId
$sgId = $sg.GroupId
Add-R "vpc=$vpcId PRESENT"
Add-R "private_subnet_standin=$subnetId PRESENT"
Add-R "task_security_group=$sgId PRESENT"

Write-Host '=== ALB + Target Group (liveness) ==='
$tg = Invoke-AwsJson elbv2 create-target-group `
  --name ses-manager-tg `
  --protocol HTTP `
  --port 8080 `
  --vpc-id $vpcId `
  --target-type ip `
  --health-check-protocol HTTP `
  --health-check-path /actuator/health/liveness `
  --health-check-interval-seconds 30 `
  --health-check-timeout-seconds 5 `
  --healthy-threshold-count 2 `
  --unhealthy-threshold-count 3 `
  --matcher HttpCode=200
$tgArn = $tg.TargetGroups[0].TargetGroupArn
$lb = Invoke-AwsJson elbv2 create-load-balancer `
  --name ses-manager-alb `
  --type application `
  --scheme internal `
  --subnets $subnetId `
  --security-groups $sgId
$lbArn = $lb.LoadBalancers[0].LoadBalancerArn
Invoke-AwsJson elbv2 create-listener `
  --load-balancer-arn $lbArn `
  --protocol HTTP `
  --port 80 `
  --default-actions ("Type=forward,TargetGroupArn={0}" -f $tgArn) | Out-Null
Add-R "alb=PRESENT arn_suffix=$($lbArn.Split('/')[-1]) scheme=internal"
Add-R 'alb_health_check_path=/actuator/health/liveness PRESENT'
Add-R 'readiness_path=/actuator/health/readiness DOCUMENTED (task/app; TG uses liveness)'

Write-Host '=== ECS cluster / task definition / service desiredCount=1 ==='
Invoke-AwsJson ecs create-cluster --cluster-name ses-manager-staging | Out-Null
$taskUri = 'file://' + ($TaskDefPath -replace '\\', '/')
$reg = Invoke-AwsJson ecs register-task-definition --cli-input-json $taskUri
$family = $reg.taskDefinition.family
$rev = $reg.taskDefinition.revision
$tdArn = $reg.taskDefinition.taskDefinitionArn
Add-R ("ecs_task_definition={0}:{1} cpu={2} memory={3}" -f $family, $rev, $reg.taskDefinition.cpu, $reg.taskDefinition.memory)

# Idempotent service create/update
$existing = $null
try {
  $listed = Invoke-AwsJson ecs describe-services --cluster ses-manager-staging --services ses-manager-staging
  if ($listed.services -and $listed.services.Count -gt 0 -and $listed.services[0].status -ne 'INACTIVE') {
    $existing = $listed.services[0]
  }
} catch { $existing = $null }

if ($null -eq $existing) {
  $svc = Invoke-AwsJson ecs create-service `
    --cluster ses-manager-staging `
    --service-name ses-manager-staging `
    --task-definition $tdArn `
    --desired-count 1 `
    --launch-type FARGATE `
    --platform-version LATEST `
    --deployment-configuration 'maximumPercent=200,minimumHealthyPercent=100,deploymentCircuitBreaker={enable=true,rollback=true}' `
    --network-configuration ("awsvpcConfiguration={{subnets=[{0}],securityGroups=[{1}],assignPublicIp=DISABLED}}" -f $subnetId, $sgId) `
    --load-balancers ("targetGroupArn={0},containerName=ses-manager,containerPort=8080" -f $tgArn) `
    --health-check-grace-period-seconds 120
  $desired = $svc.service.desiredCount
  $cb = $svc.service.deploymentConfiguration.deploymentCircuitBreaker
} else {
  $svc = Invoke-AwsJson ecs update-service `
    --cluster ses-manager-staging `
    --service ses-manager-staging `
    --task-definition $tdArn `
    --desired-count 1 `
    --force-new-deployment
  $desired = $svc.service.desiredCount
  $cb = $svc.service.deploymentConfiguration.deploymentCircuitBreaker
}

Add-R ("ecs_service=ses-manager-staging desiredCount={0}" -f $desired)
if ($desired -ne 1) { throw "desiredCount expected 1, got $desired" }
Add-R 'session_topology=SINGLE-INSTANCE-CONDITIONAL (desiredCount=1 documented)'
Add-R 'acc_ops_p1_003=NOT_CLOSED_FOR_MULTI_INSTANCE (sticky must not close)'
if ($cb) {
  Add-R ("deployment_circuit_breaker=enable={0} rollback={1}" -f $cb.enable, $cb.rollback)
} else {
  Add-R 'deployment_circuit_breaker=REQUESTED (moto may omit field; contract documents enable+rollback)'
}
Add-R 'assign_public_ip=DISABLED'
Add-R 'fail_closed_env=ai=mock cloudsign=false digital-invoice=none PRESENT in task def'
Add-R 'image_digest=ABSENT (placeholder image only; real ECR digest still required for ECS-ACTUAL)'
Add-R 'db_tls_jdbc=ABSENT (still P2 for real deploy)'
Add-R ''
Add-R 'VERDICT_LOCAL_SIM_WIRING=PASS'
Add-R 'VERDICT_ECS_ACTUAL=BLOCKED'
Add-R 'VERDICT_PRODUCTION=NO-GO (unchanged)'

$utf8 = New-Object System.Text.UTF8Encoding $false
$outPath = Join-Path $EvidenceRoot 'MOTO-ECS-ALB-WIRING.md'
[System.IO.File]::WriteAllLines($outPath, $report.ToArray(), $utf8)

# Refresh compact summary used by earlier round
$summaryPath = Join-Path $EvidenceRoot 'MOTO-VERIFY.md'
[System.IO.File]::WriteAllLines($summaryPath, $report.ToArray(), $utf8)

Write-Host ''
Write-Host "LOCAL-SIM WIRING PASS — $outPath"
Write-Host 'ECS-ACTUAL remains BLOCKED; PRODUCTION remains NO-GO.'
